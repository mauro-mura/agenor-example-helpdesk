package helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.agenor.adapters.persistence.hitl.JdbcApprovalGate;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.llm.LLMException;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.runtime.llm.ScriptedLLMProvider;

/**
 * From ticket to outbox on PostgreSQL, with a scripted LLM and fake embeddings: the request ends
 * with the draft queued; an approved or corrected draft reaches the outbox, a rejected one does
 * not; every error leaves the ticket in a state from which someone picks it up. The same tests run
 * on each transport: {@link TriageInMemoryIT}, {@link TriageRedisIT}.
 */
@SpringBootTest(properties = {"agenor.llm.provider=none", "helpdesk.orphan-decisions.interval=PT1S",
        // The tests call the ticket recovery themselves: no wait between one attempt and the next
        "helpdesk.retry.interval=PT1H", "helpdesk.retry.delay=PT0S", "helpdesk.retry.abandoned-after=PT1H",
        // Short, so that a slow draft can outlast it
        "helpdesk.draft-timeout=PT3S"})
@DirtiesContext // closes the context before Testcontainers stops the containers
@Import(TriageContract.Llm.class) // nested in a superclass, Spring would not find it by itself
abstract class TriageContract {

    static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("pgvector/pgvector:pg16");
    }

    static void database(DynamicPropertyRegistry registry, PostgreSQLContainer postgres) {
        String base = jdbcBase(postgres);
        registry.add("spring.datasource.url", () -> base);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static String jdbcBase(PostgreSQLContainer postgres) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                + "/" + postgres.getDatabaseName();
    }

    /** The concrete class's PostgreSQL. */
    abstract PostgreSQLContainer database();

    @TestConfiguration
    static class Llm {

        static final String DRAFT = "Dear customer, we are looking into your report.";

        @Bean
        ScriptedLLMProvider llm() {
            return classify(ScriptedLLMProvider.create(), "technical");
        }

        /** Classifies the ticket as {@code category}; every other request gets {@link #DRAFT}. */
        static ScriptedLLMProvider classify(ScriptedLLMProvider llm, String category) {
            return llm.orElse(request -> isClassification(request) ? category : DRAFT);
        }

        static boolean isClassification(LLMRequest request) {
            return request.messages().stream()
                    .anyMatch(m -> m.role() == LLMMessage.Role.SYSTEM && m.content().startsWith("Classify"));
        }

        /** Blocks the caller until {@code release} opens, as a slow LLM would. */
        static void await(CountDownLatch release) {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Ollama down, as the application receives it: {@code NETWORK}, retryable. */
        static LLMException down() {
            return LLMException.networkError("Ollama", "Ollama request failed: java.net.ConnectException", null);
        }

        @Bean
        @Primary
        FakeEmbeddings fakeEmbeddings() {
            return new FakeEmbeddings();
        }
    }

    @Autowired
    TicketStore tickets;

    @Autowired
    Triager triager;

    @Autowired
    ApprovalController operator;

    @Autowired
    OperatorController view;

    @Autowired
    Triage triage;

    @Autowired
    ScriptedLLMProvider llm;

    @Autowired
    FakeEmbeddings embeddings;

    @Autowired
    TransactionTemplate transaction;

    @AfterEach
    void restore() {
        llm.reset();
        Llm.classify(llm, "technical");
        embeddings.failure = null;
    }

    /** The LLM fails with {@code failure} on every request, until a test restores it. */
    void failing(LLMException failure) {
        llm.orElse(request -> {
            throw failure;
        });
    }

    @Test
    void withoutACategoryWithASpecialistGoesToTheManualQueue() throws Exception {
        Llm.classify(llm, "not sure");
        Ticket ticket = newTicket();

        assertThat(triage.triage(ticket).get(10, TimeUnit.SECONDS)).isEqualTo(TicketState.MANUAL);
        assertThat(tickets.manual()).filteredOn(t -> t.id().equals(ticket.id())).singleElement()
                .satisfies(t -> assertThat(t.category()).isEqualTo("other"));
    }

    @Test
    void withTheLlmDownItRetriesThenMovesOn() throws Exception {
        failing(Llm.down());
        Ticket ticket = newTicket();

        assertThat(triage.triage(ticket).get(10, TimeUnit.SECONDS)).isEqualTo(TicketState.RETRY);

        Llm.classify(llm, "technical");
        triage.resume().get(10, TimeUnit.SECONDS);
        assertThat(state(ticket)).isEqualTo(TicketState.AWAITING_APPROVAL);
        assertThat(pending(ticket)).isNotNull();
    }

    @Test
    void withTheLlmDownTooOftenGoesToTheManualQueue() throws Exception {
        failing(Llm.down());
        Ticket ticket = newTicket();

        triage.triage(ticket).get(10, TimeUnit.SECONDS);
        triage.resume().get(10, TimeUnit.SECONDS);
        triage.resume().get(10, TimeUnit.SECONDS);

        assertThat(tickets.manual()).filteredOn(t -> t.id().equals(ticket.id())).singleElement()
                .satisfies(t -> {
                    assertThat(t.attempts()).isEqualTo(3);
                    assertThat(t.reason()).contains("NETWORK").contains("after 3 attempts");
                });
    }

    /** An error that will not pass by itself (a model never pulled) goes to a person at once. */
    @Test
    void aMissingModelGoesStraightToTheManualQueue() throws Exception {
        failing(new LLMException("model 'llama3.2' not found", null, LLMException.ErrorType.MODEL_NOT_FOUND,
                "Ollama", "llama3.2", 404, false));
        Ticket ticket = newTicket();

        assertThat(triage.triage(ticket).get(10, TimeUnit.SECONDS)).isEqualTo(TicketState.MANUAL);
    }

    /** The LLM receives neither the customer's email nor their IBAN, from the triager or the specialist. */
    @Test
    void personalDataDoesNotReachTheLlm() throws Exception {
        Ticket ticket = new Ticket(UUID.randomUUID(), "customer@example.com", "Refund",
                "Write to me at jane.doe@example.com and refund to IT60X0542811101000000123456");
        tickets.saveClaimed(ticket, Duration.ofHours(1));

        triage.triage(ticket).get(10, TimeUnit.SECONDS);

        assertThat(llm.requests().stream().flatMap(r -> r.messages().stream())
                .filter(m -> m.role() == LLMMessage.Role.USER).map(LLMMessage::content))
                .hasSize(2).allSatisfy(text -> assertThat(text)
                .contains("[REDACTED]")
                .doesNotContain("jane.doe@example.com")
                .doesNotContain("IT60X0542811101000000123456"));
    }

    /** Without embeddings the draft is written anyway, without sources, and the operator sees it. */
    @Test
    void withoutEmbeddingsTheDraftHasNoSources() throws Exception {
        embeddings.failure = new IllegalStateException("embeddings down");
        Ticket ticket = newTicket();

        assertThat(triage.triage(ticket).get(10, TimeUnit.SECONDS)).isEqualTo(TicketState.AWAITING_APPROVAL);
        String requestId = pending(ticket).requestId();
        assertThat(view.drafts()).filteredOn(d -> d.requestId().equals(requestId)).singleElement()
                .satisfies(d -> assertThat(d.sources()).isEmpty());
    }

    /** The request ends with the draft queued, before and regardless of the operator. */
    @Test
    void theRequestEndsBeforeTheDecision() throws Exception {
        Ticket ticket = newTicket();

        DialogueMessage reply = triager.triage(ticket).get(10, TimeUnit.SECONDS).orElseThrow();

        assertThat(reply.performative()).isEqualTo(Performative.INFORM);
        assertThat(reply.contentAs(String.class)).isEqualTo(Specialist.AWAITING_APPROVAL);
        assertThat(pending(ticket).expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(7)));
        assertThat(tickets.sent(ticket.id())).isEmpty();
    }

    /** The operator sees the draft next to the customer's request, from the JDBC gate. */
    @Test
    void theOperatorSeesTicketAndDraft() throws Exception {
        Ticket ticket = triaged();
        String requestId = pending(ticket).requestId();

        assertThat(view.drafts()).filteredOn(d -> d.requestId().equals(requestId)).singleElement()
                .satisfies(d -> {
                    assertThat(d.request()).isEqualTo(ticket.body());
                    assertThat(d.draft()).isEqualTo(Llm.DRAFT);
                    assertThat(d.category()).isEqualTo("technical");
                    assertThat(d.sender()).isEqualTo(ticket.sender());
                    // The draft carries the technical knowledge it used, the error 500 page first
                    assertThat(d.sources()).hasSize(Specialist.SOURCES).first().isEqualTo("Error 500 at login");
                });
    }

    @Test
    void anApprovedDraftReachesTheOutbox() throws Exception {
        Ticket ticket = triaged();

        operator.decide(pending(ticket).requestId(),
                new ApprovalController.Decision(ApprovalController.Choice.APPROVE, null));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> tickets.sent(ticket.id()), sent -> !sent.isEmpty());
        // The recovery (every second) finds the same decision: it must not send a second time
        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))
                .until(() -> tickets.sent(ticket.id()), sent -> sent.size() == 1);
        assertThat(tickets.sent(ticket.id())).containsExactly(Llm.DRAFT);
        assertThat(state(ticket)).isEqualTo(TicketState.SENT);
    }

    /**
     * The request was created by another JVM, now stopped, and no future waits for it. The decision
     * taken afterwards still reaches the outbox, once.
     */
    @Test
    void anApprovalAfterARestartReachesTheOutbox() throws Exception {
        Ticket ticket = pendingBeforeTheRestart();

        operator.decide(pending(ticket).requestId(),
                new ApprovalController.Decision(ApprovalController.Choice.APPROVE, null));

        assertThat(sentOnce(ticket)).isEqualTo("Draft written before the restart.");
    }

    @Test
    void aCorrectionAfterARestartReachesTheOutbox() throws Exception {
        Ticket ticket = pendingBeforeTheRestart();

        operator.decide(pending(ticket).requestId(),
                new ApprovalController.Decision(ApprovalController.Choice.MODIFY, "Corrected text."));

        assertThat(sentOnce(ticket)).isEqualTo("Corrected text.");
    }

    /** A draft sent for approval by a gate that then closes, as a stopped JVM would. */
    private Ticket pendingBeforeTheRestart() {
        Ticket ticket = newTicket();
        tickets.queueDraft(ticket.id());
        Draft draft = new Draft(ticket.id(), "technical", ticket.sender(), "Re: " + ticket.subject(),
                "Draft written before the restart.", List.of());
        String url = jdbcBase(database());
        JdbcApprovalGate gateBeforeTheRestart = new JdbcApprovalGate(
                new DriverManagerDataSource(url, database().getUsername(), database().getPassword()), url);
        gateBeforeTheRestart.requestApproval(ApprovalRequest.of("specialist-technical", ReplySender.ACTION, draft,
                Duration.ofHours(8), Map.of("ticketId", ticket.id().toString())));
        gateBeforeTheRestart.close();
        return ticket;
    }

    /** The only text sent for the ticket, after giving the recovery time to pass again. */
    private String sentOnce(Ticket ticket) {
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> tickets.sent(ticket.id()), sent -> !sent.isEmpty());
        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))
                .until(() -> tickets.sent(ticket.id()), sent -> sent.size() == 1);
        return tickets.sent(ticket.id()).getFirst();
    }

    @Test
    void aModifiedDraftReachesTheOutboxAsCorrected() throws Exception {
        Ticket ticket = triaged();

        operator.decide(pending(ticket).requestId(),
                new ApprovalController.Decision(ApprovalController.Choice.MODIFY, "Corrected text."));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> tickets.sent(ticket.id()), sent -> !sent.isEmpty());
        assertThat(tickets.sent(ticket.id())).containsExactly("Corrected text.");
    }

    @Test
    void aRejectedDraftDoesNotReachTheOutbox() throws Exception {
        Ticket ticket = triaged();

        operator.decide(pending(ticket).requestId(),
                new ApprovalController.Decision(ApprovalController.Choice.REJECT, "wrong tone"));

        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .until(() -> tickets.sent(ticket.id()), List::isEmpty);
        assertThat(state(ticket)).isEqualTo(TicketState.REJECTED);
    }

    /** A ticket whose draft is already awaiting approval. */
    private Ticket triaged() throws Exception {
        Ticket ticket = newTicket();
        triager.triage(ticket).get(10, TimeUnit.SECONDS);
        return ticket;
    }

    /** A ticket received by this node, which triages it: no pass of {@link Triage#resume} takes it. */
    Ticket newTicket() {
        Ticket ticket = ticket();
        tickets.saveClaimed(ticket, Duration.ofHours(1));
        return ticket;
    }

    static Ticket ticket() {
        return new Ticket(UUID.randomUUID(), "customer@example.com", "Error 500 at login",
                "Since this morning the login answers with an error 500.");
    }

    TicketState state(Ticket ticket) {
        return tickets.state(ticket.id()).orElseThrow();
    }

    /** A ticket that went back nowhere: a failure after the reply was sent changes nothing. */
    @Test
    void aSentTicketStaysSent() throws Exception {
        Ticket ticket = triaged();
        operator.decide(pending(ticket).requestId(),
                new ApprovalController.Decision(ApprovalController.Choice.APPROVE, null));
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> tickets.sent(ticket.id()), sent -> !sent.isEmpty());

        failing(Llm.down());
        assertThat(triage.triage(ticket).get(10, TimeUnit.SECONDS)).isEqualTo(TicketState.SENT);
        assertThat(tickets.sent(ticket.id())).hasSize(1);
    }

    /**
     * The draft takes longer than the triager waits: the ticket goes to the retry queue, and the
     * retry starts while the first draft is still being written. Only one draft reaches the operator.
     */
    @Test
    void aDraftLaterThanTheTriagerWaitsIsQueuedOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        llm.orElse(request -> {
            if (Llm.isClassification(request)) {
                return "technical";
            }
            if (first.getAndSet(false)) {
                Llm.await(release);
            }
            return Llm.DRAFT;
        });
        Ticket ticket = newTicket();

        assertThat(triage.triage(ticket).get(10, TimeUnit.SECONDS)).isEqualTo(TicketState.RETRY);
        CompletableFuture<Void> retry = triage.resume();
        release.countDown();
        retry.get(15, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> state(ticket) == TicketState.AWAITING_APPROVAL);
        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))
                .until(() -> pendingFor(ticket).size() == 1);
    }

    /** A pass claims the tickets and returns: it does not hold the scheduler while the LLM thinks. */
    @Test
    void aPassDoesNotWaitForTheLlm() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        llm.orElse(request -> {
            Llm.await(release);
            return "not sure";
        });
        Ticket ticket = ticket();
        tickets.save(ticket); // as a node without the triager leaves it

        CompletableFuture<Void> pass = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> triage.resume());
        assertThat(pass).isNotDone();
        release.countDown();
        pass.get(10, TimeUnit.SECONDS);
        assertThat(state(ticket)).isEqualTo(TicketState.MANUAL);
    }

    /**
     * Two nodes claiming at the same moment never get the same ticket, and neither waits for the
     * other: node A holds its claim in an open transaction while node B claims.
     */
    @Test
    void aTicketIsClaimedOnce() throws Exception {
        List<UUID> saved = IntStream.range(0, 20).mapToObj(i -> {
            Ticket ticket = ticket();
            tickets.save(ticket);
            return ticket.id();
        }).toList();
        CountDownLatch claimedByA = new CountDownLatch(1);
        CountDownLatch claimedByB = new CountDownLatch(1);

        var nodeA = CompletableFuture.supplyAsync(() -> transaction.execute(status -> {
            List<Ticket> claimed = tickets.claimDue(Duration.ofHours(1), 10);
            claimedByA.countDown();
            Llm.await(claimedByB);
            return claimed;
        }));
        claimedByA.await(10, TimeUnit.SECONDS);
        List<Ticket> claimedOnB;
        try {
            claimedOnB = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> tickets.claimDue(Duration.ofHours(1), 1000));
        } finally {
            claimedByB.countDown();
        }
        Set<UUID> byA = ids(nodeA.get(10, TimeUnit.SECONDS), saved);
        Set<UUID> byB = ids(claimedOnB, saved);

        assertThat(byA).as("claimed by both").filteredOn(byB::contains).isEmpty();
        assertThat(byA.size() + byB.size()).isEqualTo(saved.size());
    }

    private static Set<UUID> ids(List<Ticket> claimed, List<UUID> among) {
        return claimed.stream().map(Ticket::id).filter(among::contains).collect(Collectors.toSet());
    }

    private List<ApprovalRequest> pendingFor(Ticket ticket) {
        return operator.pending().stream()
                .filter(r -> ticket.id().toString().equals(r.metadata().get("ticketId")))
                .toList();
    }

    private ApprovalRequest pending(Ticket ticket) {
        return Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> operator.pending().stream()
                        .filter(r -> ticket.id().toString().equals(r.metadata().get("ticketId")))
                        .findFirst(), Optional::isPresent)
                .orElseThrow();
    }
}
