package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** {@link JdbcHoldRoutingStore} against a real Postgres (migration V10, §9.3, milestone 5). */
class JdbcHoldRoutingStoreTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static DataSource dataSource;
    private static JdbcHoldRoutingStore store;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        dataSource =
                new SimpleDriverDataSource(
                        new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // Exactly V10's hold_routing table.
            statement.execute(
                    """
                    CREATE TABLE hold_routing (
                        hold_id    TEXT PRIMARY KEY,
                        pool_key   TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
        }
        store = new JdbcHoldRoutingStore(dataSource);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void emptyTable() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE hold_routing");
        }
    }

    @Test
    @DisplayName("save then load round-trips the pool key")
    void saveThenLoadRoundTrips() {
        store.save("h1", "42:SL:GENERAL");
        assertEquals("42:SL:GENERAL", store.load("h1").orElseThrow());
    }

    @Test
    @DisplayName("load for an unrouted hold is empty, not an error")
    void loadUnknownHoldIsEmpty() {
        assertTrue(store.load("never-allocated").isEmpty());
    }

    @Test
    @DisplayName("save is idempotent: a hold's pool never changes once written")
    void saveIsIdempotent() {
        store.save("h1", "42:SL:GENERAL");
        store.save("h1", "99:CC:TATKAL"); // a duplicate write, e.g. a retried Allocate

        assertEquals("42:SL:GENERAL", store.load("h1").orElseThrow(), "the first write wins");
    }

    @Test
    @DisplayName("delete removes the route, and is safe to call on an already-gone hold")
    void deleteIsIdempotent() {
        store.save("h1", "42:SL:GENERAL");
        store.delete("h1");
        store.delete("h1");

        assertTrue(store.load("h1").isEmpty());
    }
}
