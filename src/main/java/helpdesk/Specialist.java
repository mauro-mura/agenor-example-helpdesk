package helpdesk;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.runtime.guardrail.GuardrailChain;

/**
 * Writes a draft reply and sends it for approval; only an approved draft reaches the outbox.
 * <p>
 * The operator's wait is detached from the conversation: the request ends as soon as the draft is
 * queued, with {@code INFORM "awaiting approval"}, and sending starts from the decision. So neither
 * the request nor its commitment has to last for hours, as {@code dialog-protocol.md} advises: a
 * dialogue is for exchanges of seconds or minutes, and a long wait belongs in the approval queue.
 * What links a decision to sending is the gate's future, which lives in memory; after a restart
 * {@link OrphanDecisions} rebuilds the link.
 * <p>
 * A slow LLM can outlast the triager's wait: the triager schedules a retry while the draft is
 * still being written here. The draft is queued anyway, and the ticket leaves the retry queue;
 * the retry, if it has already started, finds the ticket moved on and writes no second draft.
 */
public class Specialist extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(Specialist.class);

    /** A human operator answers in hours: one working day. */
    static final Duration APPROVAL_TIMEOUT = Duration.ofHours(8);

    /** How many knowledge base documents go into the prompt. */
    static final int SOURCES = 2;

    /** The content of the {@code INFORM} that ends the request. */
    public static final String AWAITING_APPROVAL = "awaiting approval";

    /**
     * The content of the {@code INFORM} when the draft is dropped: an earlier attempt for the same
     * ticket timed out on the triager's side but went on here, and its draft is already queued.
     */
    public static final String ALREADY_HANDLED = "already handled";

    private final DialogueCapability dialogue = new DialogueCapability(this);
    private final Category category;
    private final LLMProvider llm;
    private final ApprovalGate approvals;
    private final ReplySender sender;
    private final KnowledgeStore<Category> knowledge;
    private final GuardrailChain guardrails;
    private final TicketStore tickets;

    public Specialist(Category category, LLMProvider llm, ApprovalGate approvals, ReplySender sender,
                      KnowledgeStore<Category> knowledge, GuardrailChain guardrails, TicketStore tickets) {
        super(category.specialist().orElseThrow(), "Specialist " + category.key());
        this.category = category;
        this.llm = llm;
        this.approvals = approvals;
        this.sender = sender;
        this.knowledge = knowledge;
        this.guardrails = guardrails;
        this.tickets = tickets;
    }

    @DialogueHandler(performatives = Performative.REQUEST)
    public void onTicket(DialogueMessage request) {
        Ticket ticket = request.contentAs(Ticket.class);
        dialogue.agree(request);

        // Neither the LLM nor the embedding provider sees the personal data the guardrail recognises
        String customerText = guardrails.applyInput("Subject: " + ticket.subject() + "\n\n" + ticket.body(),
                GuardrailContext.of(getAgentId()));
        List<KnowledgeDocument<Category>> sources = sources(customerText);
        LLMRequest prompt = LLMRequest.builder()
                .systemMessage("You are customer support, " + category.key() + " department."
                        + " Write a short, polite reply to the customer, in the language of their message."
                        + " Use only the information below where it is relevant; do not make up procedures.\n\n"
                        + sources.stream().map(d -> "## " + d.title() + "\n" + d.content())
                                .collect(Collectors.joining("\n\n")))
                .userMessage(customerText)
                .build();
        llm.chat(prompt)
                .thenAccept(reply -> {
                    // One draft per ticket: the state changes only if the ticket is still to be triaged
                    if (!tickets.queueDraft(ticket.id())) {
                        log.info("Draft for ticket {} dropped: the ticket has moved on", ticket.id());
                        dialogue.inform(request, ALREADY_HANDLED);
                        return;
                    }
                    Draft draft = new Draft(ticket.id(), category.key(), ticket.sender(),
                            "Re: " + ticket.subject(), reply.content(),
                            sources.stream().map(KnowledgeDocument::id).toList());
                    ApprovalRequest approval = ApprovalRequest.of(getAgentId(), ReplySender.ACTION, draft,
                            APPROVAL_TIMEOUT, Map.of("ticketId", ticket.id().toString()));
                    CompletableFuture<ApprovalDecision> decision;
                    try {
                        decision = approvals.requestApproval(approval);
                    } catch (RuntimeException e) {
                        tickets.unqueueDraft(ticket.id());
                        throw e;
                    }
                    decision.whenComplete((made, error) -> {
                        if (error != null) {
                            log.warn("Draft for ticket {} not decided: {}", ticket.id(), error.toString());
                        } else {
                            sender.execute(approval.requestId(), draft, made);
                        }
                    });
                    log.info("Draft for ticket {} awaiting approval: {}", ticket.id(), approval.requestId());
                    dialogue.inform(request, AWAITING_APPROVAL);
                })
                .exceptionally(error -> {
                    log.warn("Draft for ticket {} not written", ticket.id(), error);
                    dialogue.failure(request, String.valueOf(error.getMessage()));
                    return null;
                });
    }

    /**
     * The category's knowledge, or none if embeddings do not answer: the operator sees a draft
     * without sources for what it is, while a stuck ticket nobody sees.
     */
    private List<KnowledgeDocument<Category>> sources(String customerText) {
        try {
            return knowledge.searchByCategory(customerText, category, SOURCES);
        } catch (RuntimeException e) {
            log.warn("Knowledge unavailable, draft without sources: {}", e.toString());
            return List.of();
        }
    }
}
