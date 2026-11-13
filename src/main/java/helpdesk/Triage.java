package helpdesk;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import jakarta.annotation.PreDestroy;

/**
 * Brings every ticket to a state from which someone picks it up: awaiting approval, to retry
 * later, or in the manual queue. No ticket is left without a way forward.
 * <p>
 * Only a node that runs the triager triages. Any node accepts tickets: one without the triager
 * saves them, and a node with it claims them on its next pass, as it claims the tickets whose
 * retry is due. A claim lasts {@code helpdesk.retry.abandoned-after}, longer than a triage can
 * take, so two nodes never triage the same ticket at once, and a ticket left halfway by a stopped
 * process is taken again when its claim runs out.
 * <p>
 * In Agenor 0.41 the dead letter queue does not fit here: it records and does not retry, and it
 * collects messages whose handlers fail, while triage fails inside a future. The ticket is already
 * in PostgreSQL, so its state lives there.
 */
@Service
public class Triage {

    private static final Logger log = LoggerFactory.getLogger(Triage.class);

    /** How many tickets one pass claims. */
    static final int BATCH = 20;

    private final Triager triager;
    private final TicketStore tickets;
    private final boolean triagerHere;
    private final int maxAttempts;
    private final Duration delay;
    private final Duration lease;
    /**
     * Where triages run: neither the request thread nor the scheduler's waits for the LLM, and the
     * scheduler's single thread also runs the other jobs.
     */
    private final ExecutorService passes = Executors.newVirtualThreadPerTaskExecutor();

    public Triage(Triager triager, TicketStore tickets,
                  @Value("${helpdesk.agents:triager,specialists}") Set<String> agents,
                  @Value("${helpdesk.retry.attempts:3}") int maxAttempts,
                  @Value("${helpdesk.retry.delay:PT2M}") Duration delay,
                  @Value("${helpdesk.retry.abandoned-after:PT5M}") Duration lease) {
        this.triager = triager;
        this.tickets = tickets;
        this.triagerHere = agents.contains("triager");
        this.maxAttempts = maxAttempts;
        this.delay = delay;
        this.lease = lease;
    }

    /** A new ticket: triaged here if this node runs the triager, otherwise left for one that does. */
    public void receive(Ticket ticket) {
        if (triagerHere) {
            tickets.saveClaimed(ticket, lease);
            passes.execute(() -> triage(ticket));
        } else {
            tickets.save(ticket);
        }
    }

    /** Triages and records the outcome; the future completes once the state is written. */
    public CompletableFuture<TicketState> triage(Ticket ticket) {
        return triager.triage(ticket)
                .handle((reply, error) -> error != null ? failed(ticket, error) : outcome(ticket, reply));
    }

    @Scheduled(fixedDelayString = "${helpdesk.retry.interval:PT1M}")
    void scheduledResume() {
        resume();
    }

    /**
     * Claims the tickets due and starts their triage without waiting for it; the future completes
     * when all of them are done.
     */
    public CompletableFuture<Void> resume() {
        if (!triagerHere) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(tickets.claimDue(lease, BATCH).stream()
                .map(ticket -> {
                    log.info("Ticket {}: triaging", ticket.id());
                    return CompletableFuture.supplyAsync(() -> triage(ticket), passes)
                            .thenCompose(state -> state);
                })
                .toArray(CompletableFuture[]::new));
    }

    @PreDestroy
    void stop() {
        passes.shutdownNow();
    }

    private TicketState outcome(Ticket ticket, Optional<DialogueMessage> reply) {
        if (reply.isEmpty()) {
            return manual(ticket, "no category with a specialist");
        }
        DialogueMessage message = reply.get();
        if (message.performative() == Performative.INFORM) {
            // The specialist has queued the draft, or found the ticket already moved on
            return current(ticket);
        }
        // FAILURE or REFUSE from the specialist: usually its LLM was not there
        return retry(ticket, message.performative() + ": " + message.content());
    }

    private TicketState failed(Ticket ticket, Throwable error) {
        String reason = String.valueOf(error.getCause() != null ? error.getCause() : error);
        return switch (NextStep.after(error)) {
            case RETRY -> retry(ticket, reason);
            case MANUAL -> manual(ticket, reason);
        };
    }

    private TicketState retry(Ticket ticket, String reason) {
        Optional<Integer> attempts = tickets.retry(ticket.id(), reason, delay);
        if (attempts.isEmpty()) {
            return current(ticket);
        }
        if (attempts.get() >= maxAttempts) {
            return manual(ticket, reason + " (after " + attempts.get() + " attempts)");
        }
        log.warn("Ticket {}: retry in {} ({})", ticket.id(), delay, reason);
        return TicketState.RETRY;
    }

    private TicketState manual(Ticket ticket, String reason) {
        if (!tickets.toManual(ticket.id(), reason)) {
            return current(ticket);
        }
        log.warn("Ticket {}: manual queue ({})", ticket.id(), reason);
        return TicketState.MANUAL;
    }

    /** A step that changed nothing, because the ticket had already moved on: where it is now. */
    private TicketState current(Ticket ticket) {
        return tickets.state(ticket.id()).orElseThrow();
    }
}
