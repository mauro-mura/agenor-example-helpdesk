package helpdesk;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tickets and the outbox, on PostgreSQL.
 * <p>
 * Every change of state names the states it starts from, so that a late or repeated step cannot
 * move a ticket back: a draft that arrives after the ticket went to the manual queue, or a retry
 * that fails after the reply was sent, changes nothing. The methods that change state say whether
 * they did.
 */
@Repository
public class TicketStore {

    private static final String TO_TRIAGE = "('RECEIVED', 'RETRY')";

    private final JdbcClient jdbc;

    public TicketStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves a ticket for any node with the triager to pick up. */
    public void save(Ticket ticket) {
        jdbc.sql("INSERT INTO ticket (id, sender, subject, body) VALUES (?, ?, ?, ?)")
                .params(ticket.id(), ticket.sender(), ticket.subject(), ticket.body())
                .update();
    }

    /** Saves a ticket that this node triages at once: no other node takes it for {@code lease}. */
    public void saveClaimed(Ticket ticket, Duration lease) {
        jdbc.sql("""
                INSERT INTO ticket (id, sender, subject, body, claimed_until)
                VALUES (?, ?, ?, ?, now() + CAST(? AS INTERVAL))""")
                .params(ticket.id(), ticket.sender(), ticket.subject(), ticket.body(), interval(lease))
                .update();
    }

    /**
     * Claims up to {@code limit} tickets to triage, for {@code lease}: those whose next attempt is
     * due, those saved by a node without the triager, and those whose claim ran out (the process
     * stopped halfway). Two nodes calling this at once never get the same ticket.
     */
    public List<Ticket> claimDue(Duration lease, int limit) {
        return jdbc.sql("""
                UPDATE ticket SET claimed_until = now() + CAST(? AS INTERVAL)
                WHERE id IN (
                    SELECT id FROM ticket
                    WHERE state IN %s
                      AND (retry_after IS NULL OR retry_after <= now())
                      AND (claimed_until IS NULL OR claimed_until < now())
                    ORDER BY received_at LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, sender, subject, body""".formatted(TO_TRIAGE))
                .params(interval(lease), limit)
                .query(Ticket.class)
                .list();
    }

    public Optional<Ticket> find(UUID id) {
        return jdbc.sql("SELECT id, sender, subject, body FROM ticket WHERE id = ?")
                .param(id)
                .query(Ticket.class)
                .optional();
    }

    public void classify(UUID ticketId, Category category) {
        jdbc.sql("UPDATE ticket SET category = ? WHERE id = ?")
                .params(category.key(), ticketId)
                .update();
    }

    /**
     * The specialist's draft goes to the operator only if the ticket is still to be triaged; a
     * second draft for the same ticket, from an attempt that timed out, does not.
     */
    public boolean queueDraft(UUID ticketId) {
        return transition(ticketId, TO_TRIAGE, TicketState.AWAITING_APPROVAL, null);
    }

    /** Undoes {@link #queueDraft} when the approval request could not be created. */
    public void unqueueDraft(UUID ticketId) {
        transition(ticketId, "('AWAITING_APPROVAL')", TicketState.RECEIVED, null);
    }

    public boolean toManual(UUID ticketId, String reason) {
        return transition(ticketId, TO_TRIAGE, TicketState.MANUAL, reason);
    }

    /** The operator's decision, carried out once: the caller sends the reply only if this returns true. */
    public boolean decided(UUID ticketId, TicketState state, String reason) {
        return transition(ticketId, "('AWAITING_APPROVAL')", state, reason);
    }

    /**
     * Schedules another attempt after {@code delay}; returns how many have been made, or empty if
     * the ticket has moved on in the meantime.
     */
    public Optional<Integer> retry(UUID ticketId, String reason, Duration delay) {
        return jdbc.sql("""
                UPDATE ticket SET state = 'RETRY', reason = ?, attempts = attempts + 1,
                    retry_after = now() + CAST(? AS INTERVAL), claimed_until = NULL
                WHERE id = ? AND state IN %s RETURNING attempts""".formatted(TO_TRIAGE))
                .params(reason, interval(delay), ticketId)
                .query(Integer.class)
                .optional();
    }

    public void send(Draft draft, String body, String approvalId) {
        jdbc.sql("""
                INSERT INTO outbox (ticket_id, recipient, subject, body, approval_id)
                VALUES (?, ?, ?, ?, ?)""")
                .params(draft.ticketId(), draft.recipient(), draft.subject(), body, approvalId)
                .update();
    }

    public record ManualTicket(UUID id, String sender, String subject, String body, String category,
                               String reason, int attempts) {
    }

    public List<ManualTicket> manual() {
        return jdbc.sql("""
                SELECT id, sender, subject, body, category, reason, attempts FROM ticket
                WHERE state = 'MANUAL' ORDER BY received_at""")
                .query(ManualTicket.class)
                .list();
    }

    public Optional<TicketState> state(UUID ticketId) {
        return jdbc.sql("SELECT state FROM ticket WHERE id = ?")
                .param(ticketId)
                .query((row, n) -> TicketState.valueOf(row.getString(1)))
                .optional();
    }

    public List<String> sent(UUID ticketId) {
        return jdbc.sql("SELECT body FROM outbox WHERE ticket_id = ? ORDER BY id")
                .param(ticketId)
                .query(String.class)
                .list();
    }

    private boolean transition(UUID ticketId, String from, TicketState to, String reason) {
        return jdbc.sql("""
                UPDATE ticket SET state = ?, reason = ?, retry_after = NULL, claimed_until = NULL
                WHERE id = ? AND state IN %s""".formatted(from))
                .params(to.name(), reason, ticketId)
                .update() == 1;
    }

    private static String interval(Duration duration) {
        return duration.toSeconds() + " seconds";
    }
}
