package helpdesk;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;

/**
 * The operator's view: each pending draft next to the ticket it answers. Decisions go through
 * {@link ApprovalController}.
 */
@RestController
public class OperatorController {

    public record PendingDraft(String requestId, String expiresAt, String category, String sender,
                               String subject, String request, String draft, List<String> sources) {
    }

    private final ApprovalGate approvals;
    private final TicketStore tickets;
    private final KnowledgeStore<Category> knowledge;

    public OperatorController(ApprovalGate approvals, TicketStore tickets, KnowledgeStore<Category> knowledge) {
        this.approvals = approvals;
        this.tickets = tickets;
        this.knowledge = knowledge;
    }

    @GetMapping("/operator/drafts")
    public List<PendingDraft> drafts() {
        return approvals.getPendingRequests().stream()
                .filter(r -> ReplySender.ACTION.equals(r.action()))
                .map(this::view)
                .toList();
    }

    private PendingDraft view(ApprovalRequest request) {
        Draft draft = request.payloadAs(Draft.class);
        String body = tickets.find(draft.ticketId()).map(Ticket::body).orElse("(ticket not found)");
        return new PendingDraft(request.requestId(), request.expiresAt().toString(), draft.category(),
                draft.recipient(), draft.subject(), body, draft.body(), titles(draft.sources()));
    }

    /** The knowledge used, by title: the operator checks that the draft agrees with it. */
    private List<String> titles(List<String> sources) {
        return sources == null ? List.of() : sources.stream()
                .map(id -> knowledge.getById(id).map(KnowledgeDocument::title).orElse(id))
                .toList();
    }
}
