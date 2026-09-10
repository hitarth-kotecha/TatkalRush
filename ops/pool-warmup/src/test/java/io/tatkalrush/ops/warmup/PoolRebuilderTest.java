package io.tatkalrush.ops.warmup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.tatkalrush.adapters.allocatorredis.RedisSeatAllocator;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.booking.Pnr;
import io.tatkalrush.domain.inventory.TravelClass;
import io.tatkalrush.ops.invariants.InvariantChecker;
import io.tatkalrush.ops.invariants.PoolSnapshot;
import io.tatkalrush.ops.invariants.RedisInvariants;
import io.tatkalrush.ops.invariants.SqlInvariants;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
 * §13.4's rebuild, against real PostgreSQL and real Redis.
 *
 * <p>The test that carries the module is {@code everyInvariantPassesAfterARebuild}.
 * It is §19.2's wording for chaos scenario C2 turned into an assertion, and it is
 * evidence rather than a restatement because INV-8 derives its expected masks from
 * {@code seat_allocations} with its own query — the two agreeing means two
 * independent derivations of the same truth agree.
 *
 * <p>The one after it, {@code complementaryJourneysShareOneBerth}, is T-3 through
 * this path: two bookings on one berth over adjacent half-open ranges. A rebuild
 * that assigned rather than OR-ed would keep whichever row the planner returned
 * last and silently free half a berth somebody paid for.
 */
class PoolRebuilderTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** Schedule 1, four segments (five stops), 6 berths in SL. */
    private static final PoolKey SL_GENERAL = new PoolKey(1L, TravelClass.SL, QuotaType.GENERAL);
    private static final PoolKey SL_TATKAL = new PoolKey(1L, TravelClass.SL, QuotaType.TATKAL);
    private static final int SL_BERTHS = 6;
    private static final int SEGMENTS = 4;

    private static Connection conn;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> redis;

    private PoolRebuilder rebuilder;

    @BeforeAll
    static void start() throws SQLException {
        POSTGRES.start();
        REDIS.start();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        conn =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        seedNetwork();

        client = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect();
        redis = connection.sync();
    }

    @AfterAll
    static void stop() throws SQLException {
        if (conn != null) {
            conn.close();
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
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "TRUNCATE ledger_entries, refunds, payment_events, payments,"
                        + " seat_allocations, passengers, bookings RESTART IDENTITY CASCADE");
            bookingSeq = 0;
            st.execute("UPDATE quota_pools SET total_berths = 6 WHERE travel_class = 'SL'");
        }
        // A fresh allocator each time: it caches pool shapes, and a stale cache
        // would let a test pass against a shape the current Redis state does not
        // have.
        rebuilder = new PoolRebuilder(conn, new RedisSeatAllocator(redis));
    }

    // ── a freshly seeded system ─────────────────────────────────────────────

    @Nested
    @DisplayName("warming a system that has only ever been seeded")
    class ColdStart {

        @Test
        void everyPoolOnALiveScheduleIsProvisioned() throws SQLException {
            var result = rebuilder.rebuildAll();

            // Schedules 1 and 2 are OPEN, 3 is CHARTED. Two SL pools each plus one
            // 3A pool on schedule 1.
            assertEquals(7, result.pools());
            assertEquals(0, result.allocationsReplayed(), "nothing is booked yet");
            assertTrue(result.shapeWarnings().isEmpty(), result.shapeWarnings().toString());
        }

        @Test
        void aProvisionedPoolIsFullyFreeOnEverySegment() throws SQLException {
            rebuilder.rebuildAll();

            var snapshot = snapshotOf(SL_GENERAL);

            assertEquals(SL_BERTHS, snapshot.berthCount());
            assertEquals(SEGMENTS, snapshot.segmentCount());
            for (int segment = 0; segment < SEGMENTS; segment++) {
                assertEquals(
                        SL_BERTHS,
                        snapshot.freeCounts()[segment],
                        "segment " + segment + " should be entirely free");
            }
        }

        @Test
        void thePoolBecomesUsableByTheAllocator() throws SQLException {
            rebuilder.rebuildAll();

            // The actual symptom this module exists to remove: before a rebuild
            // this call throws "pool not provisioned", which is what every hold
            // and every search on a seeded system did.
            var availability =
                    new RedisSeatAllocator(redis)
                            .availability(SL_GENERAL, new SegmentRange(0, SEGMENTS));

            assertEquals(SL_BERTHS, availability.freeBerths());
        }

        @Test
        @DisplayName("allocation returns real berths.id values, not derived ones")
        void theBerthIdsAreTheOnesPostgresHas() throws SQLException {
            rebuilder.rebuildAll();

            var allocated =
                    (io.tatkalrush.application.ports.AllocationResult.Allocated)
                            new RedisSeatAllocator(redis)
                                    .allocate(
                                            new io.tatkalrush.application.ports.AllocationRequest(
                                                    SL_GENERAL,
                                                    new SegmentRange(0, SEGMENTS),
                                                    2,
                                                    "warm-1",
                                                    java.time.Instant.parse("2026-09-20T00:00:00Z"),
                                                    120_000L));

            // The berths this pool's ordinals 0 and 1 actually map to. Before the
            // mapping was stored, the allocator answered scheduleId * 1000 + ordinal
            // - which the foreign key accepted whenever it happened to land inside
            // berths.id, recording the wrong berth without an error anywhere.
            assertEquals(expectedBerthIds(SL_GENERAL, 2), allocated.berthIds());
        }

        @Test
        void everyProvisionedIdExistsInPoolBerths() throws SQLException {
            rebuilder.rebuildAll();

            assertEquals(
                    expectedBerthIds(SL_GENERAL, SL_BERTHS),
                    storedMapping(SL_GENERAL),
                    "the mapping must be pool_berths in pool_ordinal order");
        }

        @Test
        @DisplayName("the mapping follows pool_ordinal, not berth id order")
        void theMappingIsInPoolOrdinalOrder() throws SQLException {
            rebuilder.rebuildAll();

            // TATKAL maps the same six berths in reverse. A query that read
            // pool_berths in berth_id order would produce GENERAL's mapping for
            // both pools - every berth at the wrong ordinal, silently.
            List<Long> tatkal = storedMapping(SL_TATKAL);
            List<Long> general = storedMapping(SL_GENERAL);

            assertEquals(expectedBerthIds(SL_TATKAL, SL_BERTHS), tatkal);
            assertNotEquals(general, tatkal, "the two pools must not share an ordering");
            assertEquals(general.reversed(), tatkal, "TATKAL is GENERAL reversed");
        }

        @Test
        void aDepartedSchedulesPoolsAreLeftAlone() throws SQLException {
            rebuilder.rebuildAll();

            // Schedule 4 is DEPARTED. Provisioning it would put inventory for a
            // train that has left into the structure search reads.
            assertFalse(
                    redis.keys("masks:*").stream().anyMatch(k -> k.startsWith("masks:4:")),
                    "found keys for the departed schedule: " + redis.keys("masks:4:*"));
        }

        @Test
        void aChartedSchedulesPoolsAreStillProvisioned() throws SQLException {
            rebuilder.rebuildAll();

            // Charting closes booking (FR-42); it does not remove the train. API-10's
            // seat map and INV-8 both still need the state to exist.
            assertNotNull(redis.get("masks:3:SL:GENERAL"));
        }

        @Test
        void noHoldsAreCreated() throws SQLException {
            rebuilder.rebuildAll();

            // In-flight holds are lost when Redis is (§9.2, C2). Postgres has no
            // record this could restore from, and inventing one would make berths
            // unsellable until a reaper that has no matching booking swept them.
            assertTrue(redis.keys("holds:*").stream().allMatch(k -> redis.zcard(k) == 0));
        }
    }

    // ── the rebuild path ────────────────────────────────────────────────────

    @Nested
    @DisplayName("rebuilding over confirmed allocations")
    class Rebuild {

        @Test
        void aConfirmedAllocationComesBackAsMaskBits() throws SQLException {
            // Berth 3 is pool_ordinal 2 in SL GENERAL; booked over [1,3).
            confirmBooking(1, "SL", "GENERAL", 1, 3, 3L);

            var result = rebuilder.rebuildAll();
            assertEquals(1, result.allocationsReplayed());

            var snapshot = snapshotOf(SL_GENERAL);
            assertEquals(0b0110L, snapshot.masks()[2], "segments 1 and 2 occupied");
            assertEquals(SL_BERTHS, snapshot.freeCounts()[0]);
            assertEquals(SL_BERTHS - 1, snapshot.freeCounts()[1]);
            assertEquals(SL_BERTHS - 1, snapshot.freeCounts()[2]);
            assertEquals(SL_BERTHS, snapshot.freeCounts()[3]);
        }

        @Test
        void complementaryJourneysShareOneBerth() throws SQLException {
            // T-3, through the rebuild path. Two bookings, one berth, adjacent
            // half-open ranges that do not overlap.
            confirmBooking(1, "SL", "GENERAL", 0, 2, 3L);
            confirmBooking(1, "SL", "GENERAL", 2, 4, 3L);

            var result = rebuilder.rebuildAll();
            assertEquals(2, result.allocationsReplayed(), "two rows, one berth");

            // OR-ed. Assignment would keep whichever row the planner returned last
            // and free half a berth somebody paid for.
            assertEquals(0b1111L, snapshotOf(SL_GENERAL).masks()[2]);
            for (int segment = 0; segment < SEGMENTS; segment++) {
                assertEquals(SL_BERTHS - 1, snapshotOf(SL_GENERAL).freeCounts()[segment]);
            }
        }

        @Test
        void aTatkalBookingDoesNotOccupyTheGeneralPool() throws SQLException {
            confirmBooking(1, "SL", "TATKAL", 0, 4, 3L);

            rebuilder.rebuildAll();

            // FR-10: separate pools over the same physical berths. The join keys on
            // the booking's quota, and dropping that would put every TATKAL sale
            // into GENERAL's masks as well.
            assertEquals(0L, snapshotOf(SL_GENERAL).masks()[2], "GENERAL is untouched");
            // Berth id 3 is coach ordinal 2. GENERAL maps that to pool_ordinal 2;
            // TATKAL maps it reversed, to 5 - 2 = 3. Same physical berth, different
            // slot - which is the whole point of storing the mapping rather than
            // computing it.
            assertEquals(0b1111L, snapshotOf(SL_TATKAL).masks()[3], "TATKAL, same berth");
        }

        @Test
        void aRebuildIsIdempotent() throws SQLException {
            confirmBooking(1, "SL", "GENERAL", 1, 3, 3L);

            rebuilder.rebuildAll();
            String masksAfterFirst = redis.get("masks:1:SL:GENERAL");
            String freeAfterFirst = redis.get("freecount:1:SL:GENERAL");

            rebuilder.rebuildAll();

            // init-pool.lua writes the whole state rather than mutating it, which is
            // what makes it safe to run after every C2 without checking whether it
            // already ran.
            assertEquals(masksAfterFirst, redis.get("masks:1:SL:GENERAL"));
            assertEquals(freeAfterFirst, redis.get("freecount:1:SL:GENERAL"));
        }

        @Test
        void aFlushallFollowedByARebuildRestoresTheSameState() throws SQLException {
            confirmBooking(1, "SL", "GENERAL", 0, 2, 3L);
            rebuilder.rebuildAll();
            String before = redis.get("masks:1:SL:GENERAL");

            redis.flushall();
            rebuilder.rebuildAll();

            // Chaos scenario C2, in miniature.
            assertEquals(before, redis.get("masks:1:SL:GENERAL"));
        }
    }

    // ── the criterion ───────────────────────────────────────────────────────

    /**
     * §19.2's wording for chaos scenario C2, turned into an assertion.
     *
     * <p><b>The checker is built explicitly rather than via {@code standard()}.</b>
     * The first version of this test called {@code InvariantChecker.standard()},
     * which is {@code SqlInvariants.all()} — so a test named for INV-8 and INV-12
     * ran neither of them. It failed, but on INV-2 and INV-6, for reasons that had
     * nothing to do with a rebuild.
     *
     * <p>The full set is run, not just the Redis three. A rebuild cannot touch
     * payments or PNRs, so narrowing to what this module can break would have been
     * defensible — but it is also exactly how a narrowed assertion quietly stops
     * covering the thing it was narrowed around. The fixture is made legitimate
     * instead: real PNRs from {@link Pnr#fromSequence} and a SUCCESS payment per
     * confirmed booking, so a failure anywhere in §14 is a real failure.
     */
    @Test
    @DisplayName("C2's acceptance: every §14 invariant passes after a rebuild")
    void everyInvariantPassesAfterARebuild() throws SQLException {
        confirmBooking(1, "SL", "GENERAL", 0, 2, 3L);
        confirmBooking(1, "SL", "GENERAL", 2, 4, 3L);
        confirmBooking(1, "SL", "GENERAL", 1, 4, 5L);
        confirmBooking(1, "3A", "GENERAL", 0, 4, 7L);
        confirmBooking(2, "SL", "TATKAL", 1, 2, 10L);

        rebuilder.rebuildAll();

        var all = new ArrayList<>(SqlInvariants.all());
        all.addAll(RedisInvariants.all(redis, 120_000L));
        var report = new InvariantChecker(all).run(conn, InvariantChecker.Mode.QUIESCED);

        // INV-8 rebuilds expected masks from seat_allocations with its own query,
        // deliberately sharing no code with the rebuilder. Two independent
        // derivations of the same truth agreeing is evidence; one derivation
        // compared against itself would be a tautology.
        assertTrue(report.passed(), report.render());

        // The suite is only evidence if it actually contains the checks this
        // module can break. Asserted, because the first version of this test did
        // not - and a green report from a suite missing INV-8 looks identical to a
        // green report from one that has it.
        assertTrue(
                report.results().stream().anyMatch(r -> r.id().equals("INV-8") && !r.skipped()),
                "INV-8 did not run: " + report.render());
        assertTrue(
                report.results().stream().anyMatch(r -> r.id().equals("INV-12") && !r.skipped()),
                "INV-12 did not run: " + report.render());
    }

    // ── shape problems ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("when pool_berths and quota_pools disagree")
    class ShapeProblems {

        @Test
        void provisioningFollowsPoolBerthsAndWarnsAboutTotalBerths() throws SQLException {
            try (Statement st = conn.createStatement()) {
                st.execute(
                        "UPDATE quota_pools SET total_berths = 99"
                                + " WHERE schedule_id = 1 AND travel_class = 'SL'"
                                + " AND quota_type = 'GENERAL'");
            }

            var result = rebuilder.rebuildAll();

            // pool_ordinal is what the allocator addresses a mask slot by, so the
            // rows are authoritative and total_berths is a declaration that is
            // wrong. Provisioning to 99 would create 93 slots no berth maps to.
            assertEquals(SL_BERTHS, snapshotOf(SL_GENERAL).berthCount());
            assertTrue(
                    result.shapeWarnings().stream().anyMatch(w -> w.contains("total_berths=99")),
                    result.shapeWarnings().toString());
        }

        @Test
        @DisplayName("a pool with a gap in its ordinals is refused, not half-provisioned")
        void aNonContiguousPoolIsRefused() throws SQLException {
            // Ordinals 0,1,2,3,4,9 for six berths: the row count is right, so a
            // size check passes, and index 5 maps to nothing while the berth at
            // ordinal 9 is outside the mask array entirely.
            try (Statement st = conn.createStatement()) {
                st.execute(
                        "UPDATE pool_berths SET pool_ordinal = 9"
                            + " WHERE pool_ordinal = 5 AND pool_id ="
                            + " (SELECT id FROM quota_pools WHERE schedule_id = 2"
                            + "  AND travel_class = 'SL' AND quota_type = 'GENERAL')");
            }
            try {
                var result = rebuilder.rebuildAll();

                assertEquals(6, result.pools(), "one of the seven is refused");
                assertNull(
                        redis.get("masks:2:SL:GENERAL"),
                        "a pool that cannot be named must not be allocatable");
                assertTrue(
                        result.shapeWarnings().stream()
                                .anyMatch(w -> w.contains("NOT provisioned")),
                        result.shapeWarnings().toString());
            } finally {
                try (Statement st = conn.createStatement()) {
                    st.execute(
                            "UPDATE pool_berths SET pool_ordinal = 5"
                                + " WHERE pool_ordinal = 9 AND pool_id ="
                                + " (SELECT id FROM quota_pools WHERE schedule_id = 2"
                                + "  AND travel_class = 'SL' AND quota_type = 'GENERAL')");
                }
            }
        }

        @Test
        void aShapeWarningDoesNotStopTheOtherPools() throws SQLException {
            try (Statement st = conn.createStatement()) {
                st.execute(
                        "UPDATE quota_pools SET total_berths = 99"
                                + " WHERE schedule_id = 1 AND travel_class = 'SL'"
                                + " AND quota_type = 'GENERAL'");
            }

            // Refusing to provision several thousand pools because one row is odd
            // would turn a data oddity into an outage.
            assertEquals(7, rebuilder.rebuildAll().pools());
            assertNotNull(redis.get("masks:2:SL:GENERAL"));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The mapping the allocator will actually use, read back from Redis. */
    private static List<Long> storedMapping(PoolKey pool) {
        String stored = redis.get("berthids:" + pool.keySuffix());
        assertNotNull(stored, "no mapping written for " + pool);
        return java.util.Arrays.stream(stored.split(",")).map(Long::valueOf).toList();
    }

    /** {@code pool_berths.berth_id} for ordinals {@code 0..count-1}, from Postgres. */
    private static List<Long> expectedBerthIds(PoolKey pool, int count) throws SQLException {
        var ids = new java.util.ArrayList<Long>(count);
        try (Statement st = conn.createStatement();
                var rs =
                        st.executeQuery(
                                ("SELECT pb.berth_id FROM pool_berths pb"
                                    + " JOIN quota_pools q ON q.id = pb.pool_id"
                                    + " WHERE q.schedule_id = %d AND q.travel_class = '%s'"
                                    + "   AND q.quota_type = '%s' AND pb.pool_ordinal < %d"
                                    + " ORDER BY pb.pool_ordinal")
                                        .formatted(
                                                pool.scheduleId(),
                                                pool.travelClass().code(),
                                                pool.quotaType().name(),
                                                count))) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return List.copyOf(ids);
    }

    private static PoolSnapshot snapshotOf(PoolKey pool) {
        String masks = redis.get("masks:" + pool.keySuffix());
        String free = redis.get("freecount:" + pool.keySuffix());
        assertNotNull(masks, "no masks key for " + pool);
        assertNotNull(free, "no freecount key for " + pool);
        return PoolSnapshot.decode(masks, free);
    }

    private static int bookingSeq;

    /**
     * A CONFIRMED booking with one allocation and one captured payment, as
     * ConfirmBooking would leave it.
     *
     * <p>The PNR comes from {@link Pnr#fromSequence} and the payment row exists so
     * that INV-6 and INV-2 hold. Both are cheap, and without them the full-suite
     * assertion above would fail for reasons a rebuild cannot cause — which is a
     * fixture defect wearing an invariant violation's clothes.
     */
    private static void confirmBooking(
            long scheduleId, String travelClass, String quota, int fromSeq, int toSeq, long berthId)
            throws SQLException {

        String pnr = Pnr.fromSequence(++bookingSeq).value();

        try (Statement st = conn.createStatement()) {
            st.execute(
                    ("INSERT INTO bookings (pnr, schedule_id, travel_class, quota_type, from_seq,"
                        + " to_seq, status, booking_class, passenger_count, fare_paise, user_id,"
                        + " confirmed_at) VALUES ('%s', %d, '%s', '%s', %d, %d,"
                        + " 'CONFIRMED', 'CNF', 1, 100000, 1, now())")
                            .formatted(pnr, scheduleId, travelClass, quota, fromSeq, toSeq));

            st.execute(
                    ("INSERT INTO seat_allocations (schedule_id, berth_id, booking_id, seg_range)"
                        + " SELECT %d, %d, max(id), int4range(%d, %d) FROM bookings")
                            .formatted(scheduleId, berthId, fromSeq, toSeq));

            st.execute(
                    ("INSERT INTO payments (booking_id, psp_payment_id, amount_paise, status,"
                        + " settled_at) SELECT max(id), 'psp-%s', 100000, 'SUCCESS', now()"
                        + " FROM bookings")
                            .formatted(pnr));
        }
    }

    /**
     * Three trains, deliberately small: 6 SL berths over 4 segments makes a mask
     * legible as a binary literal in an assertion.
     */
    private static void seedNetwork() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "INSERT INTO stations (code, name) VALUES"
                        + " ('NDLS','New Delhi'),('MTJ','Mathura'),('KOTA','Kota'),"
                        + " ('RTM','Ratlam'),('BCT','Mumbai Central')");

            st.execute(
                    "INSERT INTO trains (number, name, origin_station_id, dest_station_id, is_hot)"
                        + " VALUES ('12951','Rajdhani',1,5,true)");

            // Five stops, seq 0..4 - max(seq) is 4, so four segments.
            st.execute(
                    "INSERT INTO train_stops (train_id, station_id, seq, distance_km) VALUES"
                        + " (1,1,0,0.00),(1,2,1,180.50),(1,3,2,410.25),"
                        + " (1,4,3,610.75),(1,5,4,730.00)");

            st.execute(
                    "INSERT INTO coaches (train_id, code, travel_class, berth_count) VALUES"
                        + " (1,'S1','SL',6),(1,'B1','3A',4)");
            // Berth ids 1..6 are SL, 7..10 are 3A.
            st.execute(
                    "INSERT INTO berths (coach_id, ordinal, berth_type)"
                        + " SELECT 1, g, 'LOWER' FROM generate_series(0, 5) g");
            st.execute(
                    "INSERT INTO berths (coach_id, ordinal, berth_type)"
                        + " SELECT 2, g, 'LOWER' FROM generate_series(0, 3) g");

            st.execute(
                    "INSERT INTO schedules (train_id, journey_date, status, departure_at,"
                        + " chart_prepared_at) VALUES"
                        + " (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30',NULL),"
                        + " (1,'2026-10-02','OPEN','2026-10-02 16:55+05:30',NULL),"
                        + " (1,'2026-10-03','CHARTED','2026-10-03 16:55+05:30',"
                        + "     '2026-10-03 12:55+05:30'),"
                        + " (1,'2026-10-04','DEPARTED','2026-10-04 16:55+05:30',NULL)");

            // Schedule 1: SL GENERAL, SL TATKAL, 3A GENERAL. Schedules 2-4: SL both
            // quotas. Seven pools on live schedules, two on the departed one.
            st.execute(
                    "INSERT INTO quota_pools (schedule_id, travel_class, quota_type, total_berths)"
                        + " VALUES (1,'SL','GENERAL',6),(1,'SL','TATKAL',6),(1,'3A','GENERAL',4),"
                        + " (2,'SL','GENERAL',6),(2,'SL','TATKAL',6),"
                        + " (3,'SL','GENERAL',6),(3,'SL','TATKAL',6),"
                        + " (4,'SL','GENERAL',6),(4,'SL','TATKAL',6)");

            // Every SL pool maps the same six physical berths.
            //
            // GENERAL maps them in id order; TATKAL maps them REVERSED, so that
            // pool_ordinal order and berth_id order disagree for at least one pool.
            // Without that, a rebuild that read pool_berths in id order instead of
            // pool_ordinal order would produce an identical mapping and no test
            // could tell - which is exactly the ordering bug that puts every berth
            // at the wrong ordinal.
            st.execute(
                    "INSERT INTO pool_berths (pool_id, berth_id, pool_ordinal)"
                        + " SELECT q.id, b.id, b.ordinal"
                        + " FROM quota_pools q JOIN berths b ON b.coach_id = 1"
                        + " WHERE q.travel_class = 'SL' AND q.quota_type = 'GENERAL'");
            st.execute(
                    "INSERT INTO pool_berths (pool_id, berth_id, pool_ordinal)"
                        + " SELECT q.id, b.id, 5 - b.ordinal"
                        + " FROM quota_pools q JOIN berths b ON b.coach_id = 1"
                        + " WHERE q.travel_class = 'SL' AND q.quota_type = 'TATKAL'");
            st.execute(
                    "INSERT INTO pool_berths (pool_id, berth_id, pool_ordinal)"
                        + " SELECT q.id, b.id, b.ordinal"
                        + " FROM quota_pools q JOIN berths b ON b.coach_id = 2"
                        + " WHERE q.travel_class = '3A'");

            st.execute("INSERT INTO users (external_ref) VALUES ('u1')");
        }
    }
}
