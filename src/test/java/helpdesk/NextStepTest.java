package helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import dev.agenor.core.llm.LLMException;

/** For the LLM, {@code isRetryable()} decides, even where the error type alone would say otherwise. */
class NextStepTest {

    @Test
    void ollamaDownIsRetried() {
        assertThat(NextStep.after(new CompletionException(TriageContract.Llm.down()))).isEqualTo(NextStep.RETRY);
    }

    @Test
    void aHostThatDoesNotResolveGoesToAPerson() {
        var host = new LLMException("Ollama request failed: unresolved", null, LLMException.ErrorType.NETWORK,
                "Ollama", "llama3.2", null, false);
        assertThat(NextStep.after(host)).isEqualTo(NextStep.MANUAL);
    }

    @Test
    void anExhaustedQuotaGoesToAPersonARateLimitIsRetried() {
        assertThat(NextStep.after(LLMException.quotaExceeded("OpenAI", "quota"))).isEqualTo(NextStep.MANUAL);
        assertThat(NextStep.after(LLMException.rateLimit("OpenAI", "429"))).isEqualTo(NextStep.RETRY);
    }
}
