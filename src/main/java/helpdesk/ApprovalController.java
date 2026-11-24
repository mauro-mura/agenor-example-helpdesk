package helpdesk;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalRequest;

/**
 * The smallest operator interface over an {@link ApprovalGate}: the pending requests, and a
 * decision on one.
 * <p>
 * Why this class exists: the console of Agenor 0.42 has no view of approvals, so an application exposes
 * them itself. Nothing here is specific to tickets; {@link OperatorController} is the part that is.
 */
@RestController
public class ApprovalController {

    public enum Choice { APPROVE, MODIFY, REJECT }

    /** {@code text} is the new text for MODIFY, the reason for REJECT. */
    public record Decision(Choice choice, String text) {
    }

    private final ApprovalGate approvals;

    public ApprovalController(ApprovalGate approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/approvals")
    public List<ApprovalRequest> pending() {
        return approvals.getPendingRequests();
    }

    @PostMapping("/approvals/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable String requestId, @RequestBody Decision decision) {
        approvals.submit(requestId, switch (decision.choice()) {
            case APPROVE -> new ApprovalDecision.Approved();
            case MODIFY -> new ApprovalDecision.Modified(decision.text());
            case REJECT -> new ApprovalDecision.Rejected(decision.text());
        });
    }
}
