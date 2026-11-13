package helpdesk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.agenor.adapters.knowledge.EmbeddingProviderFactory;
import dev.agenor.core.knowledge.EmbeddingProvider;
import dev.agenor.core.knowledge.KnowledgeStore;

/** The specialists' knowledge base: embeddings and a store on pgvector, filled by {@link KnowledgeLoader}. */
@Configuration
public class KnowledgeConfig {

    /**
     * In Agenor 0.41 the starter builds an {@code LLMProvider} from {@code agenor.llm.*} but no
     * {@code EmbeddingProvider}, so the application builds its own.
     */
    @Bean
    EmbeddingProvider embeddings(@Value("${helpdesk.embedding.base-url}") String baseUrl,
                                 @Value("${helpdesk.embedding.model}") String model,
                                 @Value("${helpdesk.embedding.dimensions}") int dimensions) {
        return EmbeddingProviderFactory.ollama(baseUrl, model, dimensions);
    }

    @Bean
    KnowledgeStore<Category> knowledge(JdbcClient jdbc, EmbeddingProvider embeddings) {
        return new PgVectorKnowledgeStore<>(jdbc, embeddings, Category::key, Category::fromKey);
    }
}
