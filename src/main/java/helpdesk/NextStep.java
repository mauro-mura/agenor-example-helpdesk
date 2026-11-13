package helpdesk;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import dev.agenor.core.guardrail.GuardrailViolationException;
import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.llm.LLMException;

/**
 * What to do with a ticket when triage fails: try again later, or hand it to a person. For the
 * LLM, {@code isRetryable()} decides: Ollama being down is retryable, a host that does not resolve
 * is not, because it is a configuration error. {@code EmbeddingException} has no
 * {@code isRetryable()}, so there the error type decides.
 */
public enum NextStep {
    RETRY, MANUAL;

    public static NextStep after(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return switch (cause) {
            case LLMException e -> e.isRetryable() ? RETRY : MANUAL;
            case EmbeddingException e -> switch (e.getErrorType()) {
                case NETWORK, RATE_LIMIT, SERVER_ERROR -> RETRY;
                default -> MANUAL;
            };
            // The request to the specialist timed out: the specialist or its LLM was slow
            case TimeoutException e -> RETRY;
            case GuardrailViolationException e -> MANUAL;
            default -> MANUAL;
        };
    }
}
