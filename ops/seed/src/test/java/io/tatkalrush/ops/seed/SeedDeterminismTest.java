package io.tatkalrush.ops.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-0.2: the seed generator produces the §10.6 dataset deterministically in
 * ≤60 seconds, including berth types (FR-48) and ≥5,000 users (FR-69).
 *
 * <p>"Deterministically" is verified the only way that means anything: seed,
 * checksum every column of every table, wipe, seed again with the same seed, and
 * compare. A row-count assertion would pass while every route, berth type and
 * departure time differed — and §9.4's Strategy A vs B comparison would then be
 * measuring the data rather than the strategies.
 *
 * <p><b>Seeding is expensive (~21 s), so it happens three times, not seven.</b>
 * A baseline is seeded once in {@code @BeforeAll} and the read-only assertions
 * all run against it. Only the two determinism tests re-seed, and they are
 * ordered last so neither has to restore state afterwards. The naive
 * seed-per-test version of this class took 250 s, which is too slow to run on
 * every build.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedDeterminismTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    /** Every table the generator writes, in dependency order. */
    private static final List<String> SEEDED_TABLES =
            List.of(
                    "stations", "trains", "train_stops", "coaches", "berths",
                    "users", "schedules", "quota_pools", "pool_berths");

    private static Connection connection;
    private static SeedStats baseline;
    private static Map<String, String> baselineChecksums;

    @BeforeAll
    static void startMigrateAndSeed() throws SQLException {
        POSTGRES.start();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        connection =
                DriverManager.getConnection(
                        SeedMain.withBatchRewrite(POSTGRES.getJdbcUrl()),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        // The generator turns autocommit off itself, but truncateAll() runs
        // before the first generate() and commits its own TRUNCATE.
        connection.setAutoCommit(false);

        baseline = seedFresh(SeedConfig.defaults());
        baselineChecksums = checksumAll();

        System.out.println("\n=== AC-0.2 ===\n" + baseline);
    }

    @AfterAll
    static void stop() throws SQLException {
        if (connection != null) {
            connection.close();
        }
        POSTGRES.stop();
    }

    // -------------------------------------------------- read-only assertions

    @Test
    @Order(1)
    @DisplayName("AC-0.2: dataset shape and the 60-second budget")
    void datasetMatchesFr48AndCompletesInBudget() {
        assertEquals(20, baseline.trains, "FR-48: 20 trains");
        assertEquals(3, baseline.hotTrains, "FR-49: three hot trains");
        assertEquals(600, baseline.schedules, "20 trains x 30 forward days");
        assertTrue(baseline.users >= 5_000, "FR-69 floor");

        // FR-48's "roughly 300k berths total" counts BOOKABLE berth-instances -
        // pool_berths across 30 journey dates - not rows in the physical berths
        // table. Confirmed with the author 2026-09-04.
        //
        // The band is wide on purpose: the exact figure depends on the coach mix
        // the PRNG draws. What must NOT drift silently is the order of
        // magnitude, because every load profile's contention level scales with
        // how much inventory exists.
        assertTrue(
                baseline.poolBerths >= 250_000 && baseline.poolBerths <= 400_000,
                "FR-48: expected ~300k bookable berth-instances, got " + baseline.poolBerths);

        assertEquals(
                baseline.berths * 30,
                baseline.poolBerths,
                "every physical berth must appear exactly once per journey date");

        // AC-0.2's budget is a wall-clock measurement, so it is sensitive to what
        // else is running. Typical is ~21 s on the build machine; the margin is
        // wide, but a full Docker Compose stack competing for the same 8 logical
        // cores can move it. If this ever fails, check that first - a genuine
        // regression would show up as a step change, not as a value near 60 s.
        assertTrue(
                baseline.elapsedMillis <= 60_000,
                () ->
                        "AC-0.2 budget is 60 s, took "
                                + baseline.elapsedMillis
                                + " ms. Typical is ~21 s. Before treating this as a seed"
                                + " regression, check whether `docker compose` was running:"
                                + " this test starts its own PostgreSQL container and competes"
                                + " with the stack for CPU and I/O.");
    }

    @Test
    @Order(2)
    @DisplayName("FR-48: every train has 3-5 classes and 4-8 coaches; berth types are assigned")
    void coachAndBerthDistributionIsValid() throws SQLException {
        assertEquals(
                0,
                queryInt(
                        "SELECT count(*) FROM (SELECT train_id, count(DISTINCT travel_class) c"
                            + " FROM coaches GROUP BY train_id) t WHERE c < 3 OR c > 5"),
                "FR-48: every train must span 3-5 travel classes");

        assertEquals(
                0,
                queryInt(
                        "SELECT count(*) FROM (SELECT train_id, count(*) c FROM coaches"
                            + " GROUP BY train_id) t WHERE c < 4 OR c > 8"),
                "FR-48: every train must have 4-8 coaches");

        assertEquals(0, queryInt("SELECT count(*) FROM berths WHERE berth_type IS NULL"));

        // FR-38's RAC allowance is 2 x side_lower_berth_count, so a dataset with
        // no side lowers would silently have no RAC capacity anywhere.
        assertTrue(
                queryInt("SELECT count(*) FROM berths WHERE berth_type = 'SIDE_LOWER'") > 0,
                "no SIDE_LOWER berths exist; FR-38's RAC allowance would be zero everywhere");
    }

    @Test
    @Order(3)
    @DisplayName("GENERAL and TATKAL pools hold DISJOINT berths")
    void quotaPoolsAreDisjoint() throws SQLException {
        // A berth in both pools for one schedule could sell twice - once through
        // each quota - while both pools' masks stayed perfectly consistent. No
        // allocator could detect it, because neither is wrong.
        assertEquals(
                0,
                queryInt(
                        "SELECT count(*) FROM ("
                            + "  SELECT pb.berth_id, qp.schedule_id, count(DISTINCT qp.quota_type) c"
                            + "  FROM pool_berths pb JOIN quota_pools qp ON qp.id = pb.pool_id"
                            + "  GROUP BY pb.berth_id, qp.schedule_id) t WHERE c > 1"),
                "a berth appears in both GENERAL and TATKAL for one schedule");
    }

    @Test
    @Order(4)
    @DisplayName("FR-67: distance_km is monotonic per train, or fares go negative")
    void distanceIsMonotonicPerTrain() throws SQLException {
        assertEquals(
                0,
                queryInt(
                        "SELECT count(*) FROM ("
                            + "  SELECT distance_km - lag(distance_km) OVER"
                            + "    (PARTITION BY train_id ORDER BY seq) AS delta"
                            + "  FROM train_stops) t WHERE delta <= 0"),
                "FR-67 sums distance over [from_seq, to_seq); a non-monotonic value"
                        + " produces a negative fare and breaks INV-7");
    }

    @Test
    @Order(5)
    @DisplayName("FR-28: TATKAL pools carry an unlock instant, GENERAL pools do not")
    void tatkalPoolsHaveAnUnlockInstant() throws SQLException {
        assertEquals(
                0,
                queryInt("SELECT count(*) FROM quota_pools WHERE quota_type='TATKAL'"
                        + " AND opens_at IS NULL"),
                "a TATKAL pool with no opens_at is permanently open, defeating FR-28");

        assertEquals(
                0,
                queryInt("SELECT count(*) FROM quota_pools WHERE quota_type='GENERAL'"
                        + " AND opens_at IS NOT NULL"),
                "GENERAL pools are always open");
    }

    // --------------------------------------------------- re-seeding, ordered last

    @Test
    @Order(90)
    @DisplayName("AC-0.2 / FR-50: the same seed produces byte-identical data")
    void seedingIsDeterministic() throws SQLException {
        SeedStats second = seedFresh(SeedConfig.defaults());
        Map<String, String> secondChecksums = checksumAll();

        for (String table : SEEDED_TABLES) {
            assertEquals(
                    baselineChecksums.get(table),
                    secondChecksums.get(table),
                    () ->
                            "table '"
                                    + table
                                    + "' differs between two runs of the same seed. FR-50 is"
                                    + " violated and no A/B benchmark from this data is a"
                                    + " controlled comparison.");
        }

        assertEquals(baseline.poolBerths, second.poolBerths);
        assertEquals(baseline.berths, second.berths);
    }

    @Test
    @Order(91)
    @DisplayName("a different seed produces different data - the seed is actually wired up")
    void differentSeedProducesDifferentData() throws SQLException {
        // Guards the embarrassing inverse failure: a generator that ignored its
        // seed entirely would pass the determinism test perfectly.
        //
        // Ordered last so it need not restore the baseline afterwards.
        seedFresh(new SeedConfig(SeedConfig.DEFAULT_SEED + 1, 20, 5_000));

        assertNotEquals(
                baselineChecksums.get("trains"),
                checksum("trains"),
                "changing the seed must change the generated routes");
    }

    // ----------------------------------------------------------- helpers

    private static SeedStats seedFresh(SeedConfig config) throws SQLException {
        truncateAll();
        return new SeedGenerator(config).generate(connection);
    }

    private static void truncateAll() throws SQLException {
        try (Statement st = connection.createStatement()) {
            // RESTART IDENTITY so the BIGSERIAL sequences reset. Without it the
            // second run's ids differ and every checksum differs for a reason
            // that has nothing to do with determinism.
            st.execute(
                    "TRUNCATE " + String.join(", ", SEEDED_TABLES) + " RESTART IDENTITY CASCADE");
            connection.commit();
        }
    }

    private static Map<String, String> checksumAll() throws SQLException {
        var checksums = new LinkedHashMap<String, String>();
        for (String table : SEEDED_TABLES) {
            checksums.put(table, checksum(table));
        }
        return checksums;
    }

    /**
     * Content hash of a table, independent of physical row order. Ordering by the
     * serialised row rather than by id means a mismatch reflects differing DATA,
     * not merely differing insertion order.
     *
     * <p>This is what caught {@code users.created_at DEFAULT now()}: the
     * generator was deterministic, the column default was not, and no row-count
     * assertion would ever have noticed.
     */
    private static String checksum(String table) throws SQLException {
        String sql =
                "SELECT md5(string_agg(x, '|' ORDER BY x)) FROM"
                        + " (SELECT row_to_json(t)::text AS x FROM %s t) s".formatted(table);
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private int queryInt(String sql) throws SQLException {
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
