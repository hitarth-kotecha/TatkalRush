package io.tatkalrush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.adapters.web.RateLimitFilter;
import io.tatkalrush.adapters.web.SearchController;
import io.tatkalrush.application.ports.RateLimiter;
import io.tatkalrush.application.ports.SeatAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Both roles §8.3 runs from one image actually start.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code psp-sim} crash-looped for days on
 * {@code Parameter 0 of constructor in RateLimitFilter required a bean of type
 * RateLimiter}, and nothing in the build noticed. {@code RateLimitFilter} was an
 * unguarded {@code @Component} while {@code RateLimiter} is wired by
 * {@code ApplicationWiring}, which is {@code @Profile("!psp-sim")} — so the
 * simulator could not construct a filter it had no use for.
 *
 * <p>It was invisible twice over. No test ever built the {@code psp-sim} context,
 * and the running stack was serving an image built before the rate limiter
 * existed, so {@code docker compose ps} reported it healthy. The failure surfaced
 * only when the image was finally rebuilt.
 *
 * <p>Wiring mistakes of this shape are a compile-clean, test-clean, start-time
 * failure. The only thing that catches them is starting the context.
 */
class ProfileContextTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void backingServices(DynamicPropertyRegistry registry) {
        // The same variables compose.yaml's x-app-env supplies to BOTH roles. The
        // simulator gets a datasource in production too - §8.3 runs one image, and
        // the earlier attempt to give psp-sim a trimmed environment died on
        // "Failed to configure a DataSource".
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @DisplayName("app-1 and app-2: the booking role")
    class BookingRole {

        @Autowired ApplicationContext context;

        @Test
        void theContextStarts() {
            assertTrue(context.containsBean("searchTrains"));
        }

        @Test
        void theBookingApiAndItsLimiterArePresent() {
            assertEquals(1, context.getBeanNamesForType(SearchController.class).length);
            assertEquals(1, context.getBeanNamesForType(RateLimitFilter.class).length);
            assertEquals(1, context.getBeanNamesForType(RateLimiter.class).length);
            assertEquals(1, context.getBeanNamesForType(SeatAllocator.class).length);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("psp-sim")
    @DisplayName("psp-sim: the simulated payment provider role")
    class PspSimRole {

        @Autowired ApplicationContext context;

        @Test
        @DisplayName("the context starts at all - this is the test that was missing")
        void theContextStarts() {
            // If the context could not be built, @Autowired above would already
            // have failed. The assertion is here so the test reads as what it
            // checks rather than as an empty method.
            assertTrue(context.getBeanDefinitionCount() > 0);
        }

        @Test
        void nothingFromTheBookingRoleIsWired() {
            // Each of these needs a bean ApplicationWiring provides, and
            // ApplicationWiring is itself excluded under this profile. A component
            // here without a matching profile guard is a start-time crash.
            assertEquals(0, context.getBeanNamesForType(RateLimitFilter.class).length);
            assertEquals(0, context.getBeanNamesForType(SearchController.class).length);
            assertEquals(0, context.getBeanNamesForType(SeatAllocator.class).length);
        }

        @Test
        void thePspSimulatorItselfIsWired() {
            // The role has to be more than "the booking role, disabled".
            assertTrue(
                    context.getBeanNamesForType(
                                    io.tatkalrush.adapters.paymentsim.SimulatedPsp.class)
                                    .length
                            > 0,
                    "psp-sim started but has no simulator");
        }

        @Test
        void itHoldsNoRedisConnection() {
            // §8.3 caps this container at 256 MB and sizes its heap to 160 MB. A
            // Lettuce client it never calls is a measurable cost inside that.
            assertFalse(
                    context.containsBean("redisClient"),
                    "the simulator should not open a Redis connection it never uses");
        }
    }
}
