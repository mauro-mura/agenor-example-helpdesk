package helpdesk;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailViolationException;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.runtime.llm.LLMAgent;

/**
 * Classifies a ticket with the LLM and forwards it to the specialist with the request protocol.
 * A ticket in {@link Category#OTHER} has no specialist: the future completes empty and
 * {@link Triage} puts it in the manual queue.
 */
public class Triager extends LLMAgent {

    public static final String ID = "triager";

    private static final Logger log = LoggerFactory.getLogger(Triager.class);

    /*
     * A small local model given only the four names sends billing tickets elsewhere: one line per
     * category says what belongs there. The line between technical and account follows the
     * knowledge base: a sign-in that fails with an error is technical, the credentials are account.
     */
    private static final String INSTRUCTIONS = """
            Classify the support ticket into exactly one of these categories:
            billing: money - what the customer pays, how and when: charges, refunds, invoices, prices, plans, payment methods;
            technical: the product misbehaving - errors, crashes, slowness, features that do not work as expected;
            account: who can sign in and how - password, sign-in codes, locked access, profile details, team members, closing the account;
            other: anything that is not a request about the customer's own use of the service.
            The ticket may be in any language. Answer with the category word only, in English.""";

    private final DialogueCapability dialogue = new DialogueCapability(this);
    private final LLMProvider llm;
    private final TicketStore tickets;
    private final Duration draftTimeout;

    /**
     * @param draftTimeout how long to wait for the specialist. The request covers only writing the
     *                     draft, not the operator's wait: the time of a slow local LLM is enough. The
     *                     default request timeout is 30 s.
     */
    public Triager(LLMProvider llm, TicketStore tickets, Duration draftTimeout) {
        super(ID, "Triager");
        this.llm = llm;
        this.tickets = tickets;
        this.draftTimeout = draftTimeout;
    }

    /**
     * Classifies and forwards. The future completes with the specialist's reply, once the draft is
     * awaiting approval, or empty for a ticket without a specialist.
     */
    public CompletableFuture<Optional<DialogueMessage>> triage(Ticket ticket) {
        String text;
        try {
            // LLMAgent does not run the chain around the call by itself: it runs here
            text = applyInputGuardrails("Subject: " + ticket.subject() + "\n\n" + ticket.body(),
                    GuardrailContext.of(ID));
        } catch (GuardrailViolationException e) {
            return CompletableFuture.failedFuture(e);
        }
        LLMRequest request = LLMRequest.builder()
                .systemMessage(INSTRUCTIONS)
                .userMessage(text)
                .maxTokens(10)
                .temperature(0.0)
                .build();
        return llm.chat(request).thenCompose(reply -> {
            Category category = Category.fromReply(reply.content());
            tickets.classify(ticket.id(), category);
            log.info("Ticket {} classified {} (model: '{}')", ticket.id(), category, reply.content());
            return category.specialist()
                    .map(specialist -> dialogue.request(specialist, ticket, draftTimeout).thenApply(Optional::of))
                    .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
        });
    }
}
