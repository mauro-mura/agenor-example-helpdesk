package helpdesk;

import dev.agenor.core.hitl.ApprovalDecision;

/**
 * Carries out the decisions taken on one approval {@code action}, including those no future is
 * waiting for any more. Part of {@link OrphanDecisions}: Agenor 0.41 has no contract for "whoever acts
 * on a decision taken after a restart".
 */
public interface DecisionExecutor {

    /** The {@code ApprovalRequest.action()} this executor handles. */
    String action();

    /**
     * @param payload the request's payload as the JDBC gate stores it (JSON)
     */
    void execute(String requestId, String payload, ApprovalDecision decision);
}
