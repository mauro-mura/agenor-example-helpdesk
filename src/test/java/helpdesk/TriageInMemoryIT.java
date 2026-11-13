package helpdesk;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@link TriageContract} on the in-memory transport. */
@Testcontainers
class TriageInMemoryIT extends TriageContract {

    @Container
    static final PostgreSQLContainer POSTGRES = postgres();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        database(registry, POSTGRES);
    }

    @Override
    PostgreSQLContainer database() {
        return POSTGRES;
    }
}
