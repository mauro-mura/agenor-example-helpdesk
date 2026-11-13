package helpdesk;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;

import dev.agenor.core.knowledge.EmbeddingProvider;
import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;

/**
 * A {@link KnowledgeStore} on PostgreSQL with pgvector: documents survive a restart and are
 * searched by cosine similarity between embeddings.
 * <p>
 * Why this class exists: in Agenor 0.41 the only store is {@code InMemoryKnowledgeStore}, which
 * matches keywords and is empty after a restart, and there is no adapter for vector databases
 * ({@code knowledge.md}, "What this is not"). Nothing here is specific to tickets: the category
 * goes through two functions.
 * <p>
 * The contract is synchronous and the {@link EmbeddingProvider} is not, so {@link #add} and
 * {@link #search} wait for the embedding. Each row remembers the model that produced it, and the
 * store refuses to mix models, as {@code knowledge.md} asks. The vector travels as a text literal:
 * no dependency beyond the driver.
 */
public class PgVectorKnowledgeStore<C> implements KnowledgeStore<C> {

    private static final String COLUMNS = "id, title, content, category, keywords, related_ids";

    private final JdbcClient jdbc;
    private final EmbeddingProvider embeddings;
    private final Function<C, String> categoryToText;
    private final Function<String, C> textToCategory;

    public PgVectorKnowledgeStore(JdbcClient jdbc, EmbeddingProvider embeddings,
                                  Function<C, String> categoryToText, Function<String, C> textToCategory) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.categoryToText = categoryToText;
        this.textToCategory = textToCategory;
    }

    @Override
    public void add(KnowledgeDocument<C> document) {
        checkModel();
        float[] vector = embeddings.embed(document.title() + "\n\n" + document.content()).join();
        jdbc.sql("""
                INSERT INTO knowledge_document (id, title, content, category, keywords, related_ids, model_id, embedding)
                VALUES (:id, :title, :content, :category, :keywords, :related, :model, CAST(:embedding AS vector))
                ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, content = EXCLUDED.content,
                    category = EXCLUDED.category, keywords = EXCLUDED.keywords,
                    related_ids = EXCLUDED.related_ids, model_id = EXCLUDED.model_id,
                    embedding = EXCLUDED.embedding""")
                .param("id", document.id())
                .param("title", document.title())
                .param("content", document.content())
                .param("category", categoryToText.apply(document.category()))
                .param("keywords", document.keywords().toArray(String[]::new))
                .param("related", document.relatedIds().toArray(String[]::new))
                .param("model", embeddings.modelId())
                .param("embedding", literal(vector))
                .update();
    }

    @Override
    public List<KnowledgeDocument<C>> search(String query, int topK) {
        return find(query, null, topK);
    }

    @Override
    public List<KnowledgeDocument<C>> searchByCategory(String query, C category, int topK) {
        return find(query, categoryToText.apply(category), topK);
    }

    @Override
    public List<KnowledgeDocument<C>> getByCategory(C category) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM knowledge_document WHERE category = ?")
                .param(categoryToText.apply(category))
                .query((row, n) -> document(row))
                .list();
    }

    @Override
    public Optional<KnowledgeDocument<C>> getById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM knowledge_document WHERE id = ?")
                .param(id)
                .query((row, n) -> document(row))
                .optional();
    }

    @Override
    public int size() {
        return jdbc.sql("SELECT count(*) FROM knowledge_document").query(Integer.class).single();
    }

    private List<KnowledgeDocument<C>> find(String query, String category, int topK) {
        checkModel();
        float[] vector = embeddings.embed(query).join();
        return jdbc.sql("SELECT " + COLUMNS + " FROM knowledge_document"
                        + " WHERE (CAST(:category AS VARCHAR) IS NULL OR category = :category)"
                        + " ORDER BY embedding <=> CAST(:embedding AS vector) LIMIT :topK")
                .param("category", category)
                .param("embedding", literal(vector))
                .param("topK", topK)
                .query((row, n) -> document(row))
                .list();
    }

    /** Vectors from different models cannot be compared. */
    private void checkModel() {
        List<String> others = jdbc.sql("SELECT DISTINCT model_id FROM knowledge_document WHERE model_id <> ?")
                .param(embeddings.modelId())
                .query(String.class)
                .list();
        if (!others.isEmpty()) {
            throw new IllegalStateException("The store holds embeddings from " + others
                    + ", the provider uses " + embeddings.modelId() + ": they must be recomputed");
        }
    }

    private KnowledgeDocument<C> document(ResultSet row) throws SQLException {
        return new KnowledgeDocument<>(row.getString("id"), row.getString("title"), row.getString("content"),
                textToCategory.apply(row.getString("category")),
                Set.copyOf(texts(row.getArray("keywords"))), texts(row.getArray("related_ids")));
    }

    private static List<String> texts(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }

    private static String literal(float[] vector) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            text.append(i == 0 ? "" : ",").append(vector[i]);
        }
        return text.append(']').toString();
    }
}
