package helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.hitl.ApprovalRequest;
import io.lettuce.core.RedisClient;

/**
 * The application on two nodes: the triager on node A (the test's context), the specialists on
 * node B. The dialogue between them crosses Redis.
 * <p>
 * The approval is given on node A while the future waiting for it is on node B: it gets there
 * through PostgreSQL's NOTIFY ({@code hitl-persistence.md}). The recovery of
 * {@link OrphanDecisions} is turned off so that it cannot stand in for it.
 */
@Testcontainers
@SpringBootTest(properties = {"agenor.llm.provider=none", "helpdesk.agents=triager",
        "helpdesk.orphan-decisions.interval=PT1H", "helpdesk.retry.interval=PT1H"})
@DirtiesContext
@Import(TriageContract.Llm.class)
class TwoNodeRedisIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TriageContract.postgres();

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    static ConfigurableApplicationContext nodeB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TriageContract.database(registry, POSTGRES);
        registry.add("agenor.messaging.provider", () -> "redis");
        registry.add("agenor.messaging.redis.uri", TwoNodeRedisIT::uri);
    }

    private static String uri() {
        return "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379);
    }

    @BeforeAll
    static void startNodeB() {
        // Arguments, not properties(...): those are defaults, and application.yml would override them
        nodeB = new SpringApplicationBuilder(HelpdeskApplication.class, TriageContract.Llm.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + TriageContract.jdbcBase(POSTGRES),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--agenor.llm.provider=none",
                        "--agenor.messaging.provider=redis",
                        "--agenor.messaging.redis.uri=" + uri(),
                        "--agenor.runtime.name=node-b",
                        "--helpdesk.agents=specialists",
                        "--helpdesk.orphan-decisions.interval=PT1H",
                        "--helpdesk.retry.interval=PT1H");
    }

    @AfterAll
    static void stopNodeB() {
        if (nodeB != null) {
            nodeB.close();
        }
    }

    @Autowired
    TicketStore tickets;

    @Autowired
    Triager triager;

    @Autowired
    ApprovalController operator;

    @Autowired
    Triage triage;

    /**
     * A ticket received on node B, which runs no triager, waits there for node A: node B's pass
     * leaves it alone, node A's triages it.
     */
    @Test
    void aTicketReceivedWithoutATriagerIsTriagedByTheNodeWithOne() throws Exception {
        Ticket ticket = TriageContract.ticket();
        Triage onNodeB = nodeB.getBean(Triage.class);

        onNodeB.receive(ticket);
        onNodeB.resume().get(10, TimeUnit.SECONDS);
        assertThat(tickets.state(ticket.id())).contains(TicketState.RECEIVED);

        triage.resume().get(30, TimeUnit.SECONDS);
        assertThat(tickets.state(ticket.id())).contains(TicketState.AWAITING_APPROVAL);
    }

    @Test
    void theDialogueCrossesRedisAndTheDecisionReachesNodeB() throws Exception {
        Ticket ticket = TriageContract.ticket();
        tickets.saveClaimed(ticket, Duration.ofHours(1));

        DialogueMessage reply = triager.triage(ticket).get(30, TimeUnit.SECONDS).orElseThrow();
        assertThat(reply.performative()).isEqualTo(Performative.INFORM);
        assertThat(reply.contentAs(String.class)).isEqualTo(Specialist.AWAITING_APPROVAL);
        assertThat(entriesOnRedis()).as("entries written to the nodes' streams").isGreaterThanOrEqualTo(3);

        ApprovalRequest approval = Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> operator.pending().stream()
                        .filter(r -> ticket.id().toString().equals(r.metadata().get("ticketId"))).findFirst(),
                        Optional::isPresent)
                .orElseThrow();
        operator.decide(approval.requestId(),
                new ApprovalController.Decision(ApprovalController.Choice.APPROVE, null));

        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> tickets.sent(ticket.id()), sent -> !sent.isEmpty());
        assertThat(tickets.sent(ticket.id())).containsExactly(TriageContract.Llm.DRAFT);
    }

    /** How many entries have been written, in total, to the {@code agenor:node:*} streams. */
    private static long entriesOnRedis() {
        RedisClient client = RedisClient.create(uri());
        try (var connection = client.connect()) {
            long total = 0;
            for (String stream : connection.sync().keys("agenor:node:*")) {
                List<Object> info = connection.sync().xinfoStream(stream);
                total += (Long) info.get(info.indexOf("entries-added") + 1);
            }
            return total;
        } finally {
            client.shutdown();
        }
    }
}
