package helpdesk;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.knowledge.KnowledgeStore;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.guardrail.GuardrailChain;
import dev.agenor.runtime.guardrail.PiiRedactionGuardrail;

/**
 * Builds the agents as Spring beans and registers them with the runtime before it starts.
 * <p>
 * The agents need the {@link ApprovalGate} and the application's own beans, which a discovered
 * agent's constructor cannot receive; so they stay out of {@code agenor.agents.base-package} and
 * are registered here, as {@code spring-boot-starter.md} describes under "Giving an agent anything
 * else".
 * <p>
 * A node registers only the groups listed in {@code helpdesk.agents} ({@code triager},
 * {@code specialists}): usually both. Running them on two nodes sends the dialogue between them
 * over Redis. The triager bean exists on every node, so that {@link Triage} can be built, but only
 * a registered one triages: {@link Triage} asks the same property.
 */
@Configuration
public class AgentConfig {

    /** Customer text reaches the LLM and the embeddings without the personal data it recognises. */
    @Bean
    GuardrailChain guardrails() {
        return GuardrailChain.builder().addInput(new PiiRedactionGuardrail()).build();
    }

    @Bean
    Triager triager(AgenorRuntime runtime, LLMProvider llm, TicketStore tickets, GuardrailChain guardrails,
                    @Value("${helpdesk.agents:triager,specialists}") Set<String> agents,
                    @Value("${helpdesk.draft-timeout:PT2M}") Duration draftTimeout) {
        Triager triager = new Triager(llm, tickets, draftTimeout);
        triager.setGuardrailChain(guardrails);
        if (agents.contains("triager")) {
            runtime.registerAgent(triager);
        }
        return triager;
    }

    @Bean
    List<Specialist> specialists(AgenorRuntime runtime, LLMProvider llm, ApprovalGate approvals,
                                 ReplySender sender, KnowledgeStore<Category> knowledge, GuardrailChain guardrails,
                                 TicketStore tickets, @Value("${helpdesk.agents:triager,specialists}") Set<String> agents) {
        List<Specialist> specialists = List.of(
                new Specialist(Category.BILLING, llm, approvals, sender, knowledge, guardrails, tickets),
                new Specialist(Category.TECHNICAL, llm, approvals, sender, knowledge, guardrails, tickets),
                new Specialist(Category.ACCOUNT, llm, approvals, sender, knowledge, guardrails, tickets));
        if (agents.contains("specialists")) {
            specialists.forEach(runtime::registerAgent);
        }
        return specialists;
    }
}
