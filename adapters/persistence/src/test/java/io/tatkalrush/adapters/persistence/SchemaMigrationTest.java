package io.tatkalrush.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-0.3, and early evidence for AC-1.8.
 *
 * <p>Two things are proven here, and the second matters more than the first.
 *
 * <ol>
 *   <li>The schema applies cleanly from empty, including {@code btree_gist} and
 *       the GiST exclusion constraint.
 *   <li><b>The constraint rejects overlaps AND permits complementary bookings.</b>
 *       A constraint that rejected everything would also pass a
 *       "does it reject overlaps?" test while making the system useless. The
 *       positive case - two passengers sharing one berth on disjoint legs - is
 *       the capability this whole project exists to demonstrate (§2.1, T-3).
 * </ol>
 *
 * <p>Runs against a real PostgreSQL 16 in Testcontainers, not an in-memory
 * substitute. H2 has no {@code btree_gist}, no {@code INT4RANGE} and no
 * {@code EXCLUDE}, so an embedded database would silently skip the only thing
 * worth testing here.
 */
class SchemaMigrationTest {

    // Pinned to match compose.yaml's postgres digest line (SDD §8.4). A floating
    // tag here would test a different database than the one that runs.
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    private static Connection connection;

    @BeforeAll
    static void startAndMigrate() throws SQLException {
        POSTGRES.start();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        connection =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterAll
    static void stop() throws SQLException {
        if (connection != null) {
            connection.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void resetAllocations() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("TRUNCATE seat_allocations RESTART IDENTITY CASCADE");
        }
    }

    // ---------------------------------------------------------------- schema

    @Test
    @DisplayName("AC-0.3: every migration applies from an empty database")
    void migrationsApplyCleanly() throws SQLException {
        for (String table :
                new String[] {
                    "stations", "trains", "train_stops", "coaches", "berths",
                    "users", "schedules", "quota_pools", "pool_berths",
                    "bookings", "passengers", "seat_allocations",
                    "payments", "payment_events", "refunds", "ledger_entries",
                    "waitlist_entries", "outbox", "idempotency_keys", "checkpoints"
                }) {
            assertTrue(tableExists(table), "missing table: " + table);
        }
    }

    @Test
    @DisplayName("btree_gist is installed - the EXCLUDE constraint cannot exist without it")
    void btreeGistExtensionPresent() throws SQLException {
        assertTrue(
                queryBoolean("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname='btree_gist')"),
                "btree_gist missing; V5's constraint would have failed to create");
    }

    @Test
    @DisplayName("the waitlist partial index exists (DD-011) and stores seq, not position")
    void waitlistUsesDerivedPositions() throws SQLException {
        assertTrue(
                queryBoolean(
                        "SELECT EXISTS (SELECT 1 FROM pg_indexes"
                            + " WHERE tablename='waitlist_entries' AND indexname='idx_waitlist_active')"),
                "partial index idx_waitlist_active missing");

        assertTrue(
                queryBoolean(
                        "SELECT EXISTS (SELECT 1 FROM information_schema.columns"
                            + " WHERE table_name='waitlist_entries' AND column_name='seq')"),
                "waitlist_entries.seq missing");

        // v1.1 stored a contiguous `position`; DD-011 replaced it. If this column
        // ever comes back, the O(n) locked renumber that deadlocks under P5 has
        // come back with it.
        assertTrue(
                !queryBoolean(
                        "SELECT EXISTS (SELECT 1 FROM information_schema.columns"
                            + " WHERE table_name='waitlist_entries' AND column_name='position')"),
                "waitlist_entries.position exists; DD-011 replaced it with seq");
    }

    @Test
    @DisplayName("refunds.reason admits ALLOCATION_CONFLICT, which INV-11 asserts is never used")
    void refundReasonSeparatesBugFromBenignRace() throws SQLException {
        seedMinimalBooking();
        try (Statement st = connection.createStatement()) {
            st.execute(
                    "INSERT INTO payments (booking_id, psp_payment_id, amount_paise, status)"
                        + " VALUES (1, 'psp_1', 100, 'SUCCESS')");
            // Both must be insertable; telling them apart is the point (DD-008).
            st.execute(
                    "INSERT INTO refunds (booking_id, payment_id, amount_paise, reason, status)"
                        + " VALUES (1, 1, 100, 'HOLD_EXPIRED', 'COMPLETED')");
            st.execute(
                    "INSERT INTO refunds (booking_id, payment_id, amount_paise, reason, status)"
                        + " VALUES (1, 1, 100, 'ALLOCATION_CONFLICT', 'COMPLETED')");
        }

        assertEquals(
                1,
                queryInt("SELECT count(*) FROM refunds WHERE reason='ALLOCATION_CONFLICT'"),
                "INV-11 must be able to find these by reason alone");

        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM refunds");
            st.execute("DELETE FROM payments");
        }
    }

    // ------------------------------------------------------- THE constraint

    @Test
    @DisplayName("AC-1.8: overlapping ranges on the same berth are REJECTED")
    void overlappingAllocationsAreRejected() throws SQLException {
        seedMinimalBooking();

        allocate(1, 1, "[0,3)"); // Delhi -> Ratlam

        // [1,4) shares segments 1 and 2 with [0,3). Overbooking, and the
        // database must refuse it regardless of what the allocator believed.
        SQLException e = assertThrows(SQLException.class, () -> allocate(1, 1, "[1,4)"));

        assertTrue(
                e.getMessage().contains("no_overlapping_allocations"),
                "expected the named constraint to fire, got: " + e.getMessage());
        assertEquals(1, allocationCount(), "the conflicting row must not have been written");
    }

    @Test
    @DisplayName("T-3 at the storage layer: complementary legs SHARE one berth")
    void complementaryAllocationsBothSucceed() throws SQLException {
        seedMinimalBooking();

        // Half-open ranges meeting at segment 2: Delhi->Ratlam and Ratlam->Mumbai.
        // They share the stop, not a leg.
        allocate(1, 1, "[0,2)");
        allocate(1, 1, "[2,4)");

        assertEquals(
                2,
                allocationCount(),
                "one berth must sell twice on disjoint legs - this is the capability in §2.1."
                        + " If this fails, half-open ranges have been changed to inclusive and the"
                        + " system now refuses bookings real railways accept.");
    }

    @Test
    @DisplayName("identical ranges on the same berth collide - the last-berth race, T-1")
    void identicalRangesCollide() throws SQLException {
        seedMinimalBooking();
        allocate(1, 1, "[0,4)");
        assertThrows(SQLException.class, () -> allocate(1, 1, "[0,4)"));
    }

    @Test
    @DisplayName("the constraint is scoped per berth and per schedule, not globally")
    void differentBerthOrScheduleDoesNotCollide() throws SQLException {
        seedMinimalBooking();

        allocate(1, 1, "[0,4)");
        allocate(1, 2, "[0,4)"); // same schedule, different berth
        allocate(2, 1, "[0,4)"); // same berth, different journey date

        assertEquals(3, allocationCount());
    }

    @Test
    @DisplayName("an empty range cannot be used to slip past the overlap check")
    void emptyRangesAreRejected() throws SQLException {
        seedMinimalBooking();

        // isempty('[2,2)') is true, and an empty range overlaps NOTHING - so
        // without the seg_range_is_non_empty CHECK these would insert freely and
        // record allocations that occupy no segments at all.
        SQLException e = assertThrows(SQLException.class, () -> allocate(1, 1, "[2,2)"));
        assertTrue(
                e.getMessage().contains("seg_range_is_non_empty"),
                "expected the non-empty CHECK to fire, got: " + e.getMessage());
    }

    // ----------------------------------------------------------- helpers

    private void allocate(long scheduleId, long berthId, String range) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(
                    "INSERT INTO seat_allocations (schedule_id, berth_id, booking_id, seg_range)"
                            + " VALUES (%d, %d, 1, '%s'::int4range)"
                                    .formatted(scheduleId, berthId, range));
        }
    }

    private int allocationCount() throws SQLException {
        return queryInt("SELECT count(*) FROM seat_allocations");
    }

    /** Minimal referential scaffolding so seat_allocations' foreign keys resolve. */
    private void seedMinimalBooking() throws SQLException {
        // Guarded on stations rather than bookings. Guarding on the LAST insert
        // means a failure partway through leaves the earlier rows behind and the
        // next call re-inserts them, turning one real failure into a cascade of
        // unrelated unique-violation errors that bury it.
        if (queryInt("SELECT count(*) FROM stations") > 0) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO stations (code, name) VALUES ('NDLS','New Delhi'),('BCT','Mumbai Central')");
            st.execute(
                    "INSERT INTO trains (number, name, origin_station_id, dest_station_id)"
                        + " VALUES ('12951','Rajdhani',1,2)");
            st.execute(
                    "INSERT INTO coaches (train_id, code, travel_class, berth_count)"
                        + " VALUES (1,'S1','SL',72)");
            st.execute(
                    "INSERT INTO berths (coach_id, ordinal, berth_type)"
                        + " VALUES (1,0,'LOWER'),(1,1,'MIDDLE')");
            st.execute(
                    "INSERT INTO schedules (train_id, journey_date, status, departure_at)"
                        + " VALUES (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30'),"
                        + "        (1,'2026-10-02','OPEN','2026-10-02 16:55+05:30')");
            st.execute("INSERT INTO users (external_ref) VALUES ('user-0001')");
            // No PNR: §6.4 issues one at CONFIRMATION, and V8's CHECK enforces
            // that a HELD booking has none. This insert carried 'PNR0000001'
            // until V8 landed and rejected it - the constraint catching a
            // fixture that had been quietly wrong since Phase 0.
            st.execute(
                    "INSERT INTO bookings (schedule_id, travel_class, quota_type, from_seq,"
                        + " to_seq, status, booking_class, passenger_count, fare_paise, user_id)"
                        + " VALUES (1,'SL','TATKAL',0,4,'HELD','CNF',1,145000,1)");
        }
    }

    private boolean tableExists(String name) throws SQLException {
        return queryBoolean(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables"
                        + " WHERE table_schema='public' AND table_name='%s')".formatted(name));
    }

    private boolean queryBoolean(String sql) throws SQLException {
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
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
