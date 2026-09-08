package io.tatkalrush.ops.invariants;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * INV-5, INV-8 and INV-12, each proven to catch its own violation.
 *
 * <p>Every test plants the exact corruption its check exists to detect. The two
 * that matter most are {@code inv8FindsAMaskBitWithNoAllocationBehindIt} — a
 * phantom sold-out berth that no other check in the project can see — and
 * {@code inv12FindsAnInflatedFreeCount}, which is the drift DD-012 predicted.
 */
class RedisInvariantsTest {

    private static final long TTL_MILLIS = 120_000;
    // PoolKey.keySuffix() is scheduleId:travelClass.code():quotaType.code(), and
    // QuotaType.code() returns name(). "GEN" is not a quota type, and an earlier
    // version of this constant said so - producing a pool that exists in Redis and
    // not in quota_pools, which INV-8 then reported as every bit disagreeing.
    private static final String POOL = "1:SL:GENERAL";

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static Connection db;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> redis;

    private InvariantChecker checker;

    @BeforeAll
    static void start() throws SQLException {
        POSTGRES.start();
        REDIS.start();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        client = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect();
        redis = connection.sync();

        seedReference();
    }

    @AfterAll
    static void stop() throws SQLException {
        if (db != null) {
            db.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        REDIS.stop();
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() throws SQLException {
        redis.flushall();
        execute("TRUNCATE seat_allocations, passengers, bookings RESTART IDENTITY CASCADE");
        checker = new InvariantChecker(RedisInvariants.all(redis, TTL_MILLIS));
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("decoding the raw bytes")
    class Decoding {

        @Test
        void aMaskRoundTripsThroughTheWireFormat() {
            var snapshot = PoolSnapshot.decode(masksBlob(0b1010L, 0L, 0b0101L), freeBlob(2, 1, 2, 3));

            assertArrayEquals(new long[] {0b1010L, 0L, 0b0101L}, snapshot.masks());
            assertArrayEquals(new int[] {2, 1, 2, 3}, snapshot.freeCounts());
        }

        /**
         * The trap. Lua splits each mask into two 32-bit halves because Lua 5.1 has
         * no 64-bit integer (DD-002). Widening the low half with a SIGNED
         * conversion sign-extends anything with bit 31 set, filling the top of the
         * long with ones — a berth occupied on segment 31 would read as occupied on
         * every segment from 31 to 63, and INV-8 would report a violation entirely
         * of its own making.
         */
        @Test
        void bitThirtyOneDoesNotSignExtendIntoTheHighHalf() {
            long occupiedOnSegment31 = 1L << 31;

            var snapshot = PoolSnapshot.decode(masksBlob(occupiedOnSegment31), freeBlob(0));

            assertEquals(
                    occupiedOnSegment31,
                    snapshot.masks()[0],
                    "a signed widening would give 0xFFFFFFFF80000000 here");
        }

        @Test
        void theHighHalfCarriesSegmentsThirtyTwoAndUp() {
            long occupiedOnSegment40 = 1L << 40;

            assertEquals(
                    occupiedOnSegment40,
                    PoolSnapshot.decode(masksBlob(occupiedOnSegment40), freeBlob(0)).masks()[0]);
        }

        @Test
        void freeCountsAreRecomputedFromTheMasksThemselves() {
            // Three berths; berth 0 occupied on segments 0-1, berth 1 on segment 1.
            var snapshot =
                    PoolSnapshot.decode(masksBlob(0b011L, 0b010L, 0L), freeBlob(9, 9, 9));

            assertArrayEquals(
                    new int[] {2, 1, 3},
                    snapshot.freeCountsFromMasks(),
                    "recomputed from bits, never from the stored counter");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("INV-5: holds the reaper never took")
    class StaleHolds {

        @Test
        void aHoldExpiredWellBeyondTheGracePeriodIsFound() {
            redis.zadd("holds:" + POOL, (double) (System.currentTimeMillis() - 300_000), "hold-1");

            assertFails("INV-5");
        }

        /**
         * The grace period earning its place. §9.2 reaps lazily inside allocate and
         * from a background sweep, so a hold a second past its TTL is one the
         * reaper has not reached — not a leak. Zero grace would have this fire
         * constantly on an idle pool, where nothing triggers the lazy path at all.
         */
        @Test
        void aHoldJustPastItsTtlIsNotAViolation() {
            redis.zadd("holds:" + POOL, (double) (System.currentTimeMillis() - 1_000), "hold-1");

            assertTrue(checker.run(db, InvariantChecker.Mode.QUIESCED).passed());
        }

        @Test
        void aLiveHoldIsNotAViolation() {
            redis.zadd("holds:" + POOL, (double) (System.currentTimeMillis() + TTL_MILLIS), "hold-1");

            assertTrue(checker.run(db, InvariantChecker.Mode.QUIESCED).passed());
        }

        @Test
        void theReportNamesTheHoldAndHowOverdueItIs() {
            redis.zadd("holds:" + POOL, (double) (System.currentTimeMillis() - 300_000), "hold-42");

            String rendered = checker.run(db, InvariantChecker.Mode.QUIESCED).render();

            assertTrue(rendered.contains("hold-42"), rendered);
            assertTrue(rendered.contains("overdue_by_ms"), rendered);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("INV-8: Redis against Postgres")
    class MasksVersusPostgres {

        @Test
        void anEmptyPoolWithNoAllocationsMatches() {
            givenPool(0L, 0L, 0L);

            assertTrue(checker.run(db, InvariantChecker.Mode.QUIESCED).passed());
        }

        @Test
        void aMaskThatMatchesItsAllocationRowPasses() throws SQLException {
            givenConfirmedAllocation(1, 1, "[0,2)");
            givenPool(0b011L, 0L, 0L);

            var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

            assertTrue(report.passed(), report.render());
        }

        /**
         * A phantom sold-out berth: seats nobody can buy and nothing else in the
         * project can see. INV-1 passes (Postgres is self-consistent), INV-4 passes
         * (nothing is oversold), and the train quietly stops selling.
         */
        @Test
        void inv8FindsAMaskBitWithNoAllocationBehindIt() {
            givenPool(0b011L, 0L, 0L);

            assertFails("INV-8");
        }

        /**
         * The reverse, and worse: the berth is offered a second time, and the
         * exclusion constraint catches it at the next customer's confirmation -
         * after their money moved.
         */
        @Test
        void inv8FindsAnAllocationRowWithNoMaskBit() throws SQLException {
            givenConfirmedAllocation(1, 1, "[0,2)");
            givenPool(0L, 0L, 0L);

            assertFails("INV-8");
        }

        /**
         * Live holds make the comparison meaningless rather than failing it -
         * allocations are written at confirmation (FR-25), so a held berth
         * legitimately has bits and no row. The check says it could not be made
         * rather than reporting a match nobody earned.
         */
        @Test
        void aLiveHoldIsReportedAsUncheckableRatherThanMatched() {
            givenPool(0b011L, 0L, 0L);
            redis.zadd("holds:" + POOL, (double) (System.currentTimeMillis() + TTL_MILLIS), "h1");

            var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

            assertFalse(report.passed());
            assertTrue(report.render().contains("quiesce first"), report.render());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("INV-12: Redis against itself")
    class FreeCountDrift {

        @Test
        void countsThatMatchTheirMasksPass() {
            // Berth 0 occupied on segments 0-1; two berths free on each of those,
            // three free on segments 2 and 3.
            givenPoolWithCounts(new long[] {0b0011L, 0L, 0L}, new int[] {2, 2, 3, 3});

            var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

            // INV-12 specifically, not the whole report. INV-8 legitimately fails
            // here - there are mask bits with no seat_allocations row behind them,
            // which is a different fault this fixture is not about. Asserting on
            // report.passed() would tie this test to INV-8's behaviour and make it
            // fail for a reason unrelated to its name.
            assertTrue(
                    report.failures().stream().noneMatch(f -> f.id().equals("INV-12")),
                    report.render());
        }

        /**
         * DD-012's prediction. An inflated count offers berths that do not exist -
         * the allocator hands out a berth its own masks say is taken, and the
         * exclusion constraint catches it after the customer paid.
         */
        @Test
        void inv12FindsAnInflatedFreeCount() {
            givenPoolWithCounts(new long[] {0b0011L, 0L, 0L}, new int[] {3, 2, 3, 3});

            assertFails("INV-12");
        }

        /** A count that reads low hides berths that exist. Quieter, still wrong. */
        @Test
        void inv12FindsADeflatedFreeCount() {
            givenPoolWithCounts(new long[] {0b0011L, 0L, 0L}, new int[] {1, 2, 3, 3});

            assertFails("INV-12");
        }

        @Test
        void theReportNamesTheSegmentAndTheDirectionOfDrift() {
            givenPoolWithCounts(new long[] {0b0011L, 0L, 0L}, new int[] {3, 2, 3, 3});

            String rendered = checker.run(db, InvariantChecker.Mode.QUIESCED).render();

            assertTrue(rendered.contains("segment=0"), rendered);
            assertTrue(rendered.contains("drift=1"), rendered);
        }

        /**
         * INV-12 asks whether Redis agrees with ITSELF, so it must fire on drift
         * even when Postgres is perfectly consistent - which is the whole reason it
         * is separate from INV-8.
         */
        @Test
        void driftIsFoundEvenWhenPostgresAgreesWithTheMasks() throws SQLException {
            givenConfirmedAllocation(1, 1, "[0,2)");
            givenPoolWithCounts(new long[] {0b0011L, 0L, 0L}, new int[] {3, 2, 3, 3});

            var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

            assertTrue(
                    report.failures().stream().anyMatch(f -> f.id().equals("INV-12")),
                    report.render());
            assertTrue(
                    report.failures().stream().noneMatch(f -> f.id().equals("INV-8")),
                    "masks and Postgres agree; only the counter drifted:\n" + report.render());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("all three are quiesce-only")
    class Quiescence {

        @Test
        void noneOfThemRunMidLoad() {
            redis.zadd("holds:" + POOL, (double) (System.currentTimeMillis() - 300_000), "hold-1");
            givenPoolWithCounts(new long[] {0b0011L, 0L, 0L}, new int[] {9, 9, 9, 9});

            var continuous = checker.run(db, InvariantChecker.Mode.CONTINUOUS);

            assertTrue(
                    continuous.passed(),
                    "during load these divergences are expected and legitimate (§14)");
            assertTrue(continuous.render().contains("3 check(s) skipped"), continuous.render());
        }

        @Test
        void theSameStateFailsOnceQuiesced() {
            redis.zadd("holds:" + POOL, (double) (System.currentTimeMillis() - 300_000), "hold-1");
            givenPoolWithCounts(new long[] {0b0011L, 0L, 0L}, new int[] {9, 9, 9, 9});

            assertFalse(checker.run(db, InvariantChecker.Mode.QUIESCED).passed());
        }
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private void assertFails(String invariantId) {
        var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

        assertTrue(
                report.failures().stream().anyMatch(f -> f.id().equals(invariantId)),
                invariantId + " did not catch its own violation:\n" + report.render());
    }

    /** Three berths, four segments, free counts derived so only the masks vary. */
    private void givenPool(long... masks) {
        var counts = new int[4];
        for (int segment = 0; segment < counts.length; segment++) {
            long bit = 1L << segment;
            int free = 0;
            for (long mask : masks) {
                if ((mask & bit) == 0) {
                    free++;
                }
            }
            counts[segment] = free;
        }
        givenPoolWithCounts(masks, counts);
    }

    private void givenPoolWithCounts(long[] masks, int[] counts) {
        redis.set("masks:" + POOL, masksBlob(masks));
        redis.set("freecount:" + POOL, freeBlob(counts));
    }

    private void givenConfirmedAllocation(int bookingId, int berthId, String range)
            throws SQLException {
        execute(
                """
                INSERT INTO bookings (id, pnr, schedule_id, travel_class, quota_type,
                                      from_seq, to_seq, status, booking_class,
                                      passenger_count, fare_paise, user_id)
                VALUES (%d, NULL, 1, 'SL', 'GENERAL', 0, 2, 'HELD', 'CNF', 1, 100000, 1)
                """
                        .formatted(bookingId));
        execute(
                "INSERT INTO seat_allocations (schedule_id, berth_id, booking_id, seg_range)"
                    + " VALUES (1, %d, %d, '%s'::int4range)".formatted(berthId, bookingId, range));
    }

    /** The wire format, built here rather than borrowed from the encoder. */
    private static String masksBlob(long... masks) {
        var buffer = ByteBuffer.allocate(masks.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long mask : masks) {
            buffer.putInt((int) (mask & 0xFFFFFFFFL));
            buffer.putInt((int) (mask >>> 32));
        }
        return new String(buffer.array(), StandardCharsets.ISO_8859_1);
    }

    private static String freeBlob(int... counts) {
        var buffer = ByteBuffer.allocate(counts.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int count : counts) {
            buffer.putInt(count);
        }
        return new String(buffer.array(), StandardCharsets.ISO_8859_1);
    }

    private static void execute(String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static void seedReference() throws SQLException {
        execute("INSERT INTO stations (code, name) VALUES ('NDLS','New Delhi'),('BCT','Mumbai')");
        execute(
                "INSERT INTO trains (number, name, origin_station_id, dest_station_id)"
                    + " VALUES ('12951','Rajdhani',1,2)");
        execute(
                "INSERT INTO train_stops (train_id, station_id, seq, distance_km)"
                    + " VALUES (1,1,0,0.00),(1,2,1,730.00)");
        execute(
                "INSERT INTO coaches (train_id, code, travel_class, berth_count)"
                    + " VALUES (1,'S1','SL',3)");
        execute(
                "INSERT INTO berths (coach_id, ordinal, berth_type)"
                    + " SELECT 1, g, 'LOWER' FROM generate_series(0, 2) g");
        execute(
                "INSERT INTO schedules (train_id, journey_date, status, departure_at)"
                    + " VALUES (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30')");
        execute(
                "INSERT INTO quota_pools (schedule_id, travel_class, quota_type, total_berths)"
                    + " VALUES (1,'SL','GENERAL',3)");
        // pool_ordinal is §10.5's "bridge between a database row and a bit
        // position in Redis" - berth 1 is ordinal 0, and INV-8 reads the mapping
        // from here rather than deriving it.
        execute(
                "INSERT INTO pool_berths (pool_id, berth_id, pool_ordinal)"
                    + " VALUES (1, 1, 0), (1, 2, 1), (1, 3, 2)");
        execute("INSERT INTO users (external_ref) VALUES ('user-1')");
    }
}
