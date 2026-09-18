package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * <b>T-C10</b>: a stale generation's checkpoint write is rejected by the
 * database, not by application code (§9.3, DD-013).
 *
 * <p>Runs against a real Postgres, with only the {@code checkpoints} table
 * created directly — not the full Flyway migration set from {@code persistence},
 * which this module has no reason to depend on. This test is about
 * {@link JdbcCheckpointStore}'s SQL, not about migration ordering.
 */
class JdbcCheckpointStoreTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static DataSource dataSource;
    private static JdbcCheckpointStore store;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();

        var driver = new org.postgresql.Driver();
        var ds = new SimpleDriverDataSource(driver, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource = ds;

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // Exactly V7's checkpoints table (adapters/persistence/.../V7__waitlist_outbox.sql).
            statement.execute(
                    """
                    CREATE TABLE checkpoints (
                        partition_key TEXT PRIMARY KEY,
                        kafka_offset  BIGINT NOT NULL,
                        generation_id BIGINT NOT NULL,
                        mask_snapshot BYTEA  NOT NULL,
                        updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
        }

        store = new JdbcCheckpointStore(dataSource);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void emptyTable() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE checkpoints");
        }
    }

    @Test
    @DisplayName("save then load round-trips the offset, generation and snapshot bytes exactly")
    void saveThenLoadRoundTrips() {
        byte[] snapshot = {1, 2, 3, 4, 5};
        store.save("42:SL:GENERAL", 100L, 1L, snapshot);

        Optional<CheckpointStore.Checkpoint> loaded = store.load("42:SL:GENERAL");

        assertTrue(loaded.isPresent());
        assertEquals(100L, loaded.get().kafkaOffset());
        assertEquals(1L, loaded.get().generationId());
        assertArrayEquals(snapshot, loaded.get().maskSnapshot());
    }

    @Test
    @DisplayName("load on an unknown pool is empty, not an error")
    void loadUnknownPoolIsEmpty() {
        assertTrue(store.load("never-checkpointed").isEmpty());
    }

    @Test
    @DisplayName("a newer generation overwrites an older one")
    void newerGenerationOverwrites() {
        store.save("42:SL:GENERAL", 100L, 1L, new byte[] {1});
        store.save("42:SL:GENERAL", 200L, 2L, new byte[] {2});

        var loaded = store.load("42:SL:GENERAL").orElseThrow();
        assertEquals(200L, loaded.kafkaOffset());
        assertEquals(2L, loaded.generationId());
        assertArrayEquals(new byte[] {2}, loaded.maskSnapshot());
    }

    @Test
    @DisplayName("T-C10: a stale (older or equal) generation's write is silently rejected")
    void staleGenerationIsRejected() {
        store.save("42:SL:GENERAL", 500L, 5L, new byte[] {5});

        // A zombie owner, fenced from Kafka but not from Postgres, tries to write
        // a checkpoint from a generation the real owner has already superseded.
        store.save("42:SL:GENERAL", 999L, 3L, new byte[] {3, 3, 3});

        var loaded = store.load("42:SL:GENERAL").orElseThrow();
        assertEquals(500L, loaded.kafkaOffset(), "the zombie's offset must not have landed");
        assertEquals(5L, loaded.generationId());
        assertArrayEquals(new byte[] {5}, loaded.maskSnapshot());
    }

    @Test
    @DisplayName("a repeat write at the SAME generation succeeds - one owner checkpoints many times per tenure")
    void sameGenerationSucceeds() {
        store.save("42:SL:GENERAL", 500L, 5L, new byte[] {5});
        store.save("42:SL:GENERAL", 600L, 5L, new byte[] {6});

        var loaded = store.load("42:SL:GENERAL").orElseThrow();
        assertEquals(600L, loaded.kafkaOffset(), "the live owner's own later checkpoint must land");
    }

    @Test
    @DisplayName("checkpoints for different pools do not interfere")
    void differentPoolsAreIndependent() {
        store.save("1:SL:GENERAL", 10L, 1L, new byte[] {1});
        store.save("2:SL:GENERAL", 20L, 1L, new byte[] {2});

        assertEquals(10L, store.load("1:SL:GENERAL").orElseThrow().kafkaOffset());
        assertEquals(20L, store.load("2:SL:GENERAL").orElseThrow().kafkaOffset());
    }
}
