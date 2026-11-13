package helpdesk;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Brings {@code knowledge.json} into the knowledge base: the new or changed documents, at start and
 * then at intervals. If embeddings do not answer (Ollama down) the application starts anyway and
 * the next pass tries again; otherwise the knowledge base would stay empty until a restart.
 */
@Component
public class KnowledgeLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLoader.class);

    /** A document as it is written in {@code knowledge.json}. */
    record Entry(String id, String title, String content, String category, Set<String> keywords) {
    }

    private final KnowledgeStore<Category> knowledge;
    private final List<Entry> entries;

    public KnowledgeLoader(KnowledgeStore<Category> knowledge, JsonMapper json,
                           @Value("classpath:knowledge.json") Resource documents) throws IOException {
        this.knowledge = knowledge;
        try (InputStream in = documents.getInputStream()) {
            this.entries = json.readValue(in, new TypeReference<List<Entry>>() { });
        }
    }

    /** At start, before tickets arrive. */
    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    @Scheduled(initialDelayString = "${helpdesk.knowledge.reload:PT10M}",
               fixedDelayString = "${helpdesk.knowledge.reload:PT10M}")
    public void load() {
        int loaded = 0;
        try {
            for (Entry entry : entries) {
                var document = new KnowledgeDocument<>(entry.id(), entry.title(), entry.content(),
                        Category.fromKey(entry.category()), entry.keywords());
                if (!knowledge.getById(entry.id()).equals(Optional.of(document))) {
                    knowledge.add(document);
                    loaded++;
                }
            }
            if (loaded > 0) {
                log.info("Knowledge: {} documents, {} loaded now", knowledge.size(), loaded);
            }
        } catch (RuntimeException e) {
            log.warn("Knowledge not loaded ({} documents loaded before the error), will retry: {}",
                    loaded, e.toString());
        }
    }
}
