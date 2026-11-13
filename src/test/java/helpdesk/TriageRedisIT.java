package helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.agenor.core.messaging.MessageDispatcher;

/**
 * {@link TriageContract} with Redis messaging (Valkey 8). On a single node the agents talk without
 * a Redis hop; {@link TwoNodeRedisIT} sends the dialogue across the broker.
 */
@Testcontainers
class TriageRedisIT extends TriageContract {

    @Container
    static final PostgreSQLContainer POSTGRES = postgres();

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        database(registry, POSTGRES);
        registry.add("agenor.messaging.provider", () -> "redis");
        registry.add("agenor.messaging.redis.uri",
                () -> "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
    }

    @Override
    PostgreSQLContainer database() {
        return POSTGRES;
    }

    /** Without Lettuce on the classpath, {@code provider: redis} would silently stay in memory. */
    @Test
    void messagingIsRedis(@Autowired MessageDispatcher dispatcher) {
        assertThat(dispatcher.getClass().getSimpleName()).containsIgnoringCase("redis");
    }
}
