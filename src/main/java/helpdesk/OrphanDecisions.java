package helpdesk;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import dev.agenor.core.hitl.ApprovalDecision;

/**
 * Carries out the approval decisions that no future is waiting for any more, each exactly once.
 * <p>
 * Why this class exists: the future returned by {@code ApprovalGate.requestApproval} lives only in
 * the JVM that created it ({@code hitl-persistence.md}, "Local-future constraint"). After a
 * restart, a decision taken on a request that is still {@code PENDING} is recorded in the gate's
 * table, and nothing else happens. Agenor 0.41 has no way to hand such a decision to whoever should act
 * on it, so this class reads the gate's table, whose schema {@code hitl-persistence.md} documents,
 * and passes each decision to the {@link DecisionExecutor} of its {@code action}. It knows nothing
 * about tickets.
 * <p>
 * A decision is carried out once whether it arrives from here or from the future
 * ({@link #executeOnce}).
 * <p>
 * {@code helpdesk.orphan-decisions.enabled=false} turns the recovery off, to see what happens
 * without it: a decision taken after a restart is recorded, and the reply never leaves. Decisions
 * whose future is still waiting are carried out as before.
 */
@Component
public class OrphanDecisions {

    private static final Logger log = LoggerFactory.getLogger(OrphanDecisions.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ObjectProvider<DecisionExecutor> executors;
    private final String table;
    private final boolean enabled;

    /**
     * @param table   the gate's table, schema-qualified if needed
     * @param enabled whether {@link #recover} runs; {@link #executeOnce} works either way
     */
    public OrphanDecisions(JdbcClient jdbc, TransactionTemplate transaction, ObjectProvider<DecisionExecutor> executors,
                           @Value("${helpdesk.orphan-decisions.table:agenor_hitl_requests}") String table,
                           @Value("${helpdesk.orphan-decisions.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.executors = executors;
        this.table = table;
        this.enabled = enabled;
        if (!enabled) {
            log.warn("Recovery of approval decisions is off: a decision taken after a restart will not be carried out");
        }
    }

    /** Runs {@code action} unless decision {@code requestId} has already been carried out. */
    public boolean executeOnce(String requestId, Runnable action) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            int inserted = jdbc.sql("INSERT INTO executed_decision (request_id) VALUES (?) ON CONFLICT DO NOTHING")
                    .param(requestId)
                    .update();
            if (inserted == 1) {
                action.run();
            }
            return inserted == 1;
        }));
    }

    @Scheduled(fixedDelayString = "${helpdesk.orphan-decisions.interval:PT5S}")
    public void recover() {
        if (!enabled) {
            return;
        }
        // Looked up here and not in the constructor: an executor calls executeOnce, so it depends on us
        Map<String, DecisionExecutor> executors = this.executors.orderedStream()
                .collect(Collectors.toUnmodifiableMap(DecisionExecutor::action, Function.identity()));
        if (executors.isEmpty()) {
            return;
        }
        jdbc.sql("""
                SELECT r.request_id, r.action, r.payload, r.decision_type, r.decision_data
                FROM %s r
                WHERE r.status IN ('APPROVED', 'MODIFIED', 'REJECTED')
                  AND r.action IN (:actions)
                  AND NOT EXISTS (SELECT 1 FROM executed_decision e WHERE e.request_id = r.request_id)""".formatted(table))
                .param("actions", executors.keySet())
                .query((row, n) -> {
                    String requestId = row.getString("request_id");
                    ApprovalDecision decision = decision(row.getString("decision_type"), row.getString("decision_data"));
                    DecisionExecutor executor = executors.get(row.getString("action"));
                    String payload = row.getString("payload");
                    return (Runnable) () -> {
                        if (executeOnce(requestId, () -> executor.execute(requestId, payload, decision))) {
                            log.info("Decision {} on {} carried out by the recovery", requestId, executor.action());
                        }
                    };
                })
                .list()
                .forEach(Runnable::run);
    }

    private static ApprovalDecision decision(String type, String data) {
        return switch (type) {
            case "APPROVED" -> new ApprovalDecision.Approved();
            case "MODIFIED" -> new ApprovalDecision.Modified(data);
            case "REJECTED" -> new ApprovalDecision.Rejected(data);
            default -> throw new IllegalStateException("Unknown decision: " + type);
        };
    }
}
