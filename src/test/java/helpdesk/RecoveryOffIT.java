package helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * With {@code helpdesk.orphan-decisions.enabled=false}: what {@link OrphanDecisions} is there for.
 * A decision taken after a restart is recorded, and nothing else happens; a decision whose future
 * is still waiting reaches the outbox as before.
 */
@SpringBootTest(properties = {"agenor.llm.provider=none", "helpdesk.orphan-decisions.enabled=false",
        "helpdesk.orphan-decisions.interval=PT1S", "helpdesk.retry.interval=PT1H"})
@DirtiesContext // closes the context before Testcontainers stops the containers
@Import(TriageContract.Llm.class)
@Testcontainers
class RecoveryOffIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TriageContract.postgres();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TriageContract.database(registry, POSTGRES);
    }

    @Autowired
    TicketStore tickets;

    @Autowired
    Triager triager;

    @Autowired
    ApprovalController operator;

    @Test
    void anApprovalAfterARestartIsRecordedAndNothingHappens() {
        Ticket ticket = TriageContract.pendingBeforeTheRestart(tickets, POSTGRES);
        String requestId = pendingRequestId(ticket);

        operator.decide(requestId, new ApprovalController.Decision(ApprovalController.Choice.APPROVE, null));

        // The decision is in the gate's table: no longer pending
        assertThat(operator.pending()).noneMatch(r -> r.requestId().equals(requestId));
        // Several passes of the recovery would have sent it by now; none runs
        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))
                .until(() -> tickets.sent(ticket.id()), sent -> sent.isEmpty());
        assertThat(tickets.state(ticket.id())).contains(TicketState.AWAITING_APPROVAL);
    }

    @Test
    void anApprovalWithItsFutureStillWaitingReachesTheOutbox() throws Exception {
        Ticket ticket = TriageContract.ticket();
        tickets.saveClaimed(ticket, Duration.ofHours(1));
        triager.triage(ticket).get(10, TimeUnit.SECONDS);

        operator.decide(pendingRequestId(ticket),
                new ApprovalController.Decision(ApprovalController.Choice.APPROVE, null));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> tickets.sent(ticket.id()), sent -> sent.size() == 1);
    }

    private String pendingRequestId(Ticket ticket) {
        return Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> operator.pending().stream()
                        .filter(r -> ticket.id().toString().equals(r.metadata().get("ticketId")))
                        .findFirst(), r -> r.isPresent())
                .orElseThrow()
                .requestId();
    }
}
