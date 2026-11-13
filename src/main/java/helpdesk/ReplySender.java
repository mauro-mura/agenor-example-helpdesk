package helpdesk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.agenor.core.hitl.ApprovalDecision;
import tools.jackson.databind.json.JsonMapper;

/**
 * Carries out the operator's decision on a draft: approved or corrected, it goes to the outbox,
 * once, whether the decision arrives through the gate's future or through the recovery after a
 * restart. Both steps run in the transaction of {@link OrphanDecisions#executeOnce}.
 */
@Component
public class ReplySender implements DecisionExecutor {

    public static final String ACTION = "send-reply";

    private static final Logger log = LoggerFactory.getLogger(ReplySender.class);

    private final TicketStore tickets;
    private final OrphanDecisions decisions;
    private final JsonMapper json;

    public ReplySender(TicketStore tickets, OrphanDecisions decisions, JsonMapper json) {
        this.tickets = tickets;
        this.decisions = decisions;
        this.json = json;
    }

    @Override
    public String action() {
        return ACTION;
    }

    @Override
    public void execute(String requestId, String payload, ApprovalDecision decision) {
        apply(requestId, json.readValue(payload, Draft.class), decision);
    }

    /** The live path: the specialist still holds the draft and the gate's future. */
    public void execute(String requestId, Draft draft, ApprovalDecision decision) {
        decisions.executeOnce(requestId, () -> apply(requestId, draft, decision));
    }

    /** The ticket changes state once; the reply goes to the outbox only with that change. */
    private void apply(String requestId, Draft draft, ApprovalDecision decision) {
        String outcome = switch (decision) {
            case ApprovalDecision.Approved a -> send(requestId, draft, draft.body(), null, "sent");
            case ApprovalDecision.Modified m ->
                    send(requestId, draft, m.newPayloadAs(String.class), "corrected by the operator",
                            "sent with corrections");
            case ApprovalDecision.Rejected r -> tickets.decided(draft.ticketId(), TicketState.REJECTED, r.reason())
                    ? "rejected: " + r.reason() : null;
        };
        if (outcome == null) {
            log.warn("Ticket {}: decision {} not carried out, the ticket was no longer awaiting approval",
                    draft.ticketId(), requestId);
        } else {
            log.info("Ticket {}: {}", draft.ticketId(), outcome);
        }
    }

    private String send(String requestId, Draft draft, String body, String reason, String outcome) {
        if (!tickets.decided(draft.ticketId(), TicketState.SENT, reason)) {
            return null;
        }
        tickets.send(draft, body, requestId);
        return outcome;
    }
}
