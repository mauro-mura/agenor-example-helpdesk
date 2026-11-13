package helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.core.knowledge.KnowledgeStore;

/** The pgvector store against the {@link KnowledgeStore} contract, and beyond the life of one instance. */
@Testcontainers
class PgVectorKnowledgeStoreIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    static JdbcClient jdbc;

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        KnowledgeStore<Category> store = store(new FakeEmbeddings());
        store.add(new KnowledgeDocument<>("bil-1", "Double charge",
                "The second charge is reversed within five days", Category.BILLING, Set.of("charge")));
        store.add(new KnowledgeDocument<>("bil-2", "Invoice details",
                "The VAT number is changed in the billing details", Category.BILLING, Set.of("invoice")));
        store.add(new KnowledgeDocument<>("acc-1", "Forgotten password",
                "The password is reset from the login page", Category.ACCOUNT, Set.of("password")));
    }

    private static KnowledgeStore<Category> store(FakeEmbeddings embeddings) {
        return new PgVectorKnowledgeStore<>(jdbc, embeddings, Category::key, Category::fromKey);
    }

    @Test
    void theClosestComesFirst() {
        assertThat(store(new FakeEmbeddings()).search("you made a double charge on my card", 3))
                .first().extracting(KnowledgeDocument::id).isEqualTo("bil-1");
    }

    @Test
    void theCategoryNarrowsTheSearch() {
        assertThat(store(new FakeEmbeddings()).searchByCategory("forgotten password", Category.BILLING, 3))
                .extracting(KnowledgeDocument::id).containsOnly("bil-1", "bil-2");
    }

    /** Another instance, as after a restart, finds the same documents. */
    @Test
    void documentsOutliveTheInstance() {
        KnowledgeStore<Category> afterRestart = store(new FakeEmbeddings());
        assertThat(afterRestart.size()).isEqualTo(3);
        assertThat(afterRestart.getById("acc-1")).get()
                .satisfies(d -> {
                    assertThat(d.category()).isEqualTo(Category.ACCOUNT);
                    assertThat(d.keywords()).containsExactly("password");
                    assertThat(d.relatedIds()).isEmpty();
                });
        assertThat(afterRestart.getByCategory(Category.BILLING)).hasSize(2);
    }

    @Test
    void doesNotMixModels() {
        assertThatThrownBy(() -> store(new FakeEmbeddings("another-model")).search("password", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another-model");
    }
}
