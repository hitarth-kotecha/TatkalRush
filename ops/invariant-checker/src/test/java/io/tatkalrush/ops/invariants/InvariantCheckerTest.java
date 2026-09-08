package io.tatkalrush.ops.invariants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * §14's checks, each proven to catch its own violation.
 *
 * <h2>Why every test plants a violation</h2>
 *
 * <p>A checker that has never caught anything is a checker nobody has tested. It is
 * trivially easy to write nine queries that return no rows against a clean database
 * and call that a green run — and impossible to tell that apart from nine queries
 * that would return no rows against <em>any</em> database.
 *
 * <p>So each test below corrupts the data in the specific way its invariant exists
 * to detect, and requires the check to find it. That is the same discipline as
 * mutation testing, pointed at the referee.
 */
class InvariantCheckerTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    private static Connection db;
    private final InvariantChecker checker = InvariantChecker.standard();

    @BeforeAll
    static void startAndMigrate() throws SQLException {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        seedReference();
    }

    @AfterAll
    static void stop() throws SQLException {
        if (db != null) {
            db.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() throws SQLException {
        execute(
                "TRUNCATE ledger_entries, refunds, payment_events, payments,"
                    + " seat_allocations, passengers, waitlist_entries, bookings"
                    + " RESTART IDENTITY CASCADE");
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("a clean run passes")
    class Baseline {

        @Test
        void everyCheckPassesOnConsistentData() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);

            var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

            assertTrue(report.passed(), report.render());
            assertEquals(9, report.results().size(), "all nine SQL invariants ran");
        }

        /**
         * The failure mode this direction of query protects against: a run that
         * crashed in its first second leaves an empty database, and checks written
         * to return "the correct rows" would find none and call it a pass.
         *
         * <p>An empty database genuinely violates nothing. Detecting that a run
         * produced no work is the report generator's job, not an invariant's — but
         * it is worth being explicit that this is a pass and why.
         */
        @Test
        void anEmptyDatabaseViolatesNothing() {
            assertTrue(checker.run(db, InvariantChecker.Mode.QUIESCED).passed());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("each check catches its own violation")
    class Detection {

        /**
         * The EXCLUDE constraint makes this unreachable through normal writes, so
         * the violation has to be planted with the constraint temporarily dropped.
         * That is the point: INV-1 is the independent confirmation that the
         * constraint is still there and still working, and a migration that dropped
         * it would leave every other test in the project passing.
         */
        @Test
        void inv1FindsOverlappingAllocations() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            givenConfirmedBooking(2, "0000000026", 2);

            execute("ALTER TABLE seat_allocations DROP CONSTRAINT no_overlapping_allocations");
            try {
                execute(
                        "INSERT INTO seat_allocations (schedule_id, berth_id, booking_id, seg_range)"
                            + " VALUES (1, 1, 2, '[0,4)'::int4range)");

                assertFails("INV-1");
            } finally {
                // The rows go first. Re-adding an exclusion constraint over data
                // that violates it fails, and the next test then finds no
                // constraint to drop - one planted violation cascading into a
                // suite that no longer tests what it says it does.
                execute("DELETE FROM seat_allocations");
                execute(
                        "ALTER TABLE seat_allocations ADD CONSTRAINT no_overlapping_allocations"
                            + " EXCLUDE USING gist (schedule_id WITH =, berth_id WITH =,"
                            + " seg_range WITH &&)");
            }
        }

        @Test
        void inv2FindsAConfirmedBookingWithNoPayment() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            execute("DELETE FROM payments WHERE booking_id = 1");

            assertFails("INV-2");
        }

        @Test
        void inv2FindsAConfirmedBookingChargedTwice() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            execute(
                    "INSERT INTO payments (booking_id, psp_payment_id, amount_paise, status)"
                        + " VALUES (1, 'psp-duplicate', 100000, 'SUCCESS')");

            assertFails("INV-2");
        }

        /** The crash-between-settle-and-confirm case FR-23's sweep repairs. */
        @Test
        void inv3FindsMoneyCapturedAgainstABookingThatNeverAdvanced() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            execute("UPDATE bookings SET status = 'PAYMENT_PENDING', pnr = NULL WHERE id = 1");

            assertFails("INV-3");
        }

        /**
         * Per SEGMENT, not per pool. The TATKAL pool holds 2 berths; three
         * allocations overlapping on segment 1 exceed it even though each berth is
         * distinct.
         */
        @Test
        void inv4FindsASegmentOversold() throws SQLException {
            execute("ALTER TABLE seat_allocations DROP CONSTRAINT no_overlapping_allocations");
            try {
                for (int booking = 1; booking <= 3; booking++) {
                    givenConfirmedBooking(booking, pnrFor(booking), booking, "TATKAL");
                }

                assertFails("INV-4");
            } finally {
                // The rows go first. Re-adding an exclusion constraint over data
                // that violates it fails, and the next test then finds no
                // constraint to drop - one planted violation cascading into a
                // suite that no longer tests what it says it does.
                execute("DELETE FROM seat_allocations");
                execute(
                        "ALTER TABLE seat_allocations ADD CONSTRAINT no_overlapping_allocations"
                            + " EXCLUDE USING gist (schedule_id WITH =, berth_id WITH =,"
                            + " seg_range WITH &&)");
            }
        }

        /**
         * T-3's complementary journeys must NOT trip INV-4. Counting bookings
         * against capacity rather than per-segment occupancy would report the
         * capability this project exists to demonstrate as a violation.
         */
        @Test
        void inv4DoesNotFireOnComplementaryJourneysSharingABerth() throws SQLException {
            givenBooking(1, "0000000018", "CONFIRMED", "GENERAL", 0, 2);
            givenBooking(2, "0000000026", "CONFIRMED", "GENERAL", 2, 4);
            execute(
                    "INSERT INTO seat_allocations (schedule_id, berth_id, booking_id, seg_range)"
                        + " VALUES (1, 1, 1, '[0,2)'::int4range), (1, 1, 2, '[2,4)'::int4range)");
            payFor(1);
            payFor(2);

            assertTrue(
                    checker.run(db, InvariantChecker.Mode.QUIESCED).passed(),
                    "one berth sold on two disjoint legs is the point of the system");
        }

        /**
         * The test that actually proves INV-4 counts per SEGMENT.
         *
         * <p>Three complementary journeys down one berth of a two-berth pool. Per
         * segment, each carries one allocation and nothing is oversold. Counted per
         * POOL — or with the segment expansion collapsed — it reads as three
         * allocations against a capacity of two, and reports the capability this
         * project exists to demonstrate as a violation.
         *
         * <p>An earlier version of the complementary-journeys test used the
         * 72-berth GENERAL pool, where a wrong grouping still fits comfortably
         * under capacity. It passed under a mutation that removed the per-segment
         * expansion entirely, which is how this one came to be written.
         */
        @Test
        void inv4CountsPerSegmentAndNotPerPool() throws SQLException {
            givenBooking(1, "0000000018", "CONFIRMED", "TATKAL", 0, 1);
            givenBooking(2, "0000000026", "CONFIRMED", "TATKAL", 1, 2);
            givenBooking(3, "0000000034", "CONFIRMED", "TATKAL", 2, 3);
            execute(
                    "INSERT INTO seat_allocations (schedule_id, berth_id, booking_id, seg_range)"
                        + " VALUES (1, 1, 1, '[0,1)'::int4range),"
                        + "        (1, 1, 2, '[1,2)'::int4range),"
                        + "        (1, 1, 3, '[2,3)'::int4range)");
            payFor(1);
            payFor(2);
            payFor(3);

            var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

            assertTrue(
                    report.passed(),
                    "three journeys down one berth of a 2-berth pool is one allocation per "
                            + "segment, not three against capacity:\n"
                            + report.render());
        }

        @Test
        void inv6FindsADuplicatePnr() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            givenConfirmedBooking(2, "0000000026", 2);
            // The unique index has to go for the corruption to be plantable, which
            // is itself what INV-6 independently confirms is still present.
            execute("ALTER TABLE bookings DROP CONSTRAINT bookings_pnr_key");
            try {
                execute("UPDATE bookings SET pnr = '0000000018' WHERE id = 2");

                assertFails("INV-6");
            } finally {
                execute("UPDATE bookings SET pnr = '0000000026' WHERE id = 2");
                execute("ALTER TABLE bookings ADD CONSTRAINT bookings_pnr_key UNIQUE (pnr)");
            }
        }

        /** A corrupted digit no index can see. */
        @Test
        void inv6FindsAPnrWithABadCheckDigit() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            execute("UPDATE bookings SET pnr = '0000000019' WHERE id = 1");

            assertFails("INV-6");
        }

        /** More money out than in. No pricing rule makes this correct. */
        @Test
        void inv7FindsANegativeRetainedAmount() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            execute(
                    "INSERT INTO ledger_entries (booking_id, entry_type, amount_paise)"
                        + " VALUES (1, 'CHARGE', 100000), (1, 'REFUND', 150000)");

            assertFails("INV-7");
        }

        /** A ledger entry with no refund row behind it, or the reverse. */
        @Test
        void inv7FindsALedgerThatDisagreesWithTheRefundsTable() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            execute(
                    "INSERT INTO ledger_entries (booking_id, entry_type, amount_paise)"
                        + " VALUES (1, 'CHARGE', 100000), (1, 'REFUND', 90000)");
            // No matching COMPLETED refund row.

            assertFails("INV-7");
        }

        @Test
        void inv9FindsADuplicateWaitlistSequence() throws SQLException {
            givenBooking(1, null, "HELD", "GENERAL", 0, 4);
            givenBooking(2, null, "HELD", "GENERAL", 0, 4);
            execute("ALTER TABLE waitlist_entries DROP CONSTRAINT waitlist_entries_schedule_id_travel_class_entry_type_seq_key");
            try {
                execute(
                        "INSERT INTO waitlist_entries (schedule_id, travel_class, booking_id, seq, entry_type)"
                            + " VALUES (1, 'SL', 1, 1, 'WL'), (1, 'SL', 2, 1, 'WL')");

                assertFails("INV-9");
            } finally {
                execute("DELETE FROM waitlist_entries");
                execute(
                        "ALTER TABLE waitlist_entries ADD CONSTRAINT"
                            + " waitlist_entries_schedule_id_travel_class_entry_type_seq_key"
                            + " UNIQUE (schedule_id, travel_class, entry_type, seq)");
            }
        }

        /**
         * The bug writing this check exposed: terminal transitions used to leave
         * hold_expires_at populated, so a released hold reported as a violation for
         * the rest of its original TTL — firing on entirely correct behaviour.
         */
        @Test
        void inv10FindsATerminalBookingStillHoldingABerth() throws SQLException {
            givenBooking(1, null, "EXPIRED", "GENERAL", 0, 4);
            execute("UPDATE bookings SET hold_expires_at = now() + interval '2 minutes' WHERE id = 1");

            assertFails("INV-10");
        }

        @Test
        void inv10DoesNotFireOnAReleasedHoldWithTheColumnCleared() throws SQLException {
            givenBooking(1, null, "EXPIRED", "GENERAL", 0, 4);
            execute("UPDATE bookings SET hold_expires_at = NULL WHERE id = 1");

            assertTrue(checker.run(db, InvariantChecker.Mode.QUIESCED).passed());
        }

        /** One row fails the run. Not a threshold, not a rate. */
        @Test
        void inv11FindsASingleAllocationConflictRefund() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            execute(
                    "INSERT INTO refunds (booking_id, payment_id, amount_paise, reason, status)"
                        + " VALUES (1, 1, 100000, 'ALLOCATION_CONFLICT', 'COMPLETED')");

            var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

            assertFalse(report.passed());
            assertTrue(
                    report.failures().stream().anyMatch(f -> f.id().equals("INV-11")),
                    report.render());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("the report says what it did and did not ask")
    class Reporting {

        @Test
        void aFailureNamesTheOffendingRows() throws SQLException {
            givenConfirmedBooking(1, "0000000018", 1);
            execute("DELETE FROM payments WHERE booking_id = 1");

            var report = checker.run(db, InvariantChecker.Mode.QUIESCED);
            String rendered = report.render();

            // "INV-2 failed" is useless at the end of a soak. The booking id is
            // what turns the report into an investigation.
            assertTrue(rendered.contains("booking_id=1"), rendered);
            assertTrue(rendered.contains("FAILED"), rendered);
        }

        @Test
        void aQuiesceOnlyCheckIsSkippedRatherThanRunContinuously() {
            var withQuiesceOnly =
                    new InvariantChecker(
                            List.of(
                                    SqlInvariants.noAllocationConflictRefunds(),
                                    new Invariant() {
                                        @Override
                                        public String id() {
                                            return "INV-TEST";
                                        }

                                        @Override
                                        public String description() {
                                            return "only meaningful at rest";
                                        }

                                        @Override
                                        public boolean quiesceOnly() {
                                            return true;
                                        }

                                        @Override
                                        public List<String> violations(CheckContext context) {
                                            return List.of("this must never be reported mid-run");
                                        }
                                    }));

            var continuous = withQuiesceOnly.run(db, InvariantChecker.Mode.CONTINUOUS);

            assertTrue(continuous.passed(), continuous.render());
            assertTrue(continuous.render().contains("SKIP"), continuous.render());
            assertTrue(
                    continuous.render().contains("1 check(s) skipped"),
                    "'all green' and 'all green, one not asked' are different claims");
        }

        @Test
        void thatSameCheckDoesRunWhenQuiesced() {
            var withQuiesceOnly =
                    new InvariantChecker(
                            List.of(
                                    new Invariant() {
                                        @Override
                                        public String id() {
                                            return "INV-TEST";
                                        }

                                        @Override
                                        public String description() {
                                            return "only meaningful at rest";
                                        }

                                        @Override
                                        public boolean quiesceOnly() {
                                            return true;
                                        }

                                        @Override
                                        public List<String> violations(CheckContext context) {
                                            return List.of("found it");
                                        }
                                    }));

            assertFalse(withQuiesceOnly.run(db, InvariantChecker.Mode.QUIESCED).passed());
        }

        /** A check that cannot run has not passed. */
        @Test
        void aBrokenQueryIsReportedAsAViolationRatherThanSwallowed() {
            var broken =
                    new InvariantChecker(
                            List.of(
                                    new Invariant() {
                                        @Override
                                        public String id() {
                                            return "INV-BROKEN";
                                        }

                                        @Override
                                        public String description() {
                                            return "references a table that is not there";
                                        }

                                        @Override
                                        public List<String> violations(CheckContext context) {
                                            return context.query("SELECT * FROM no_such_table");
                                        }
                                    }));

            var report = broken.run(db, InvariantChecker.Mode.QUIESCED);

            assertFalse(report.passed(), "swallowing this is how a checker stops checking");
            assertTrue(report.render().contains("CHECK FAILED TO EXECUTE"), report.render());
        }
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private void assertFails(String invariantId) {
        var report = checker.run(db, InvariantChecker.Mode.QUIESCED);

        assertTrue(
                report.failures().stream().anyMatch(f -> f.id().equals(invariantId)),
                invariantId + " did not catch its own violation:\n" + report.render());
    }

    private static String pnrFor(int booking) {
        return io.tatkalrush.domain.booking.Pnr.fromSequence(booking).value();
    }

    private void givenConfirmedBooking(int id, String pnr, int berthId) throws SQLException {
        givenConfirmedBooking(id, pnr, berthId, "GENERAL");
    }

    private void givenConfirmedBooking(int id, String pnr, int berthId, String quota)
            throws SQLException {
        givenBooking(id, pnr, "CONFIRMED", quota, 0, 4);
        execute(
                "INSERT INTO seat_allocations (schedule_id, berth_id, booking_id, seg_range)"
                    + " VALUES (1, %d, %d, '[0,4)'::int4range)".formatted(berthId, id));
        payFor(id);
    }

    private void payFor(int bookingId) throws SQLException {
        execute(
                "INSERT INTO payments (booking_id, psp_payment_id, amount_paise, status)"
                    + " VALUES (%d, 'psp-%d', 100000, 'SUCCESS')".formatted(bookingId, bookingId));
    }

    private void givenBooking(
            int id, String pnr, String status, String quota, int fromSeq, int toSeq)
            throws SQLException {
        execute(
                """
                INSERT INTO bookings (id, pnr, schedule_id, travel_class, quota_type,
                                      from_seq, to_seq, status, booking_class,
                                      passenger_count, fare_paise, user_id)
                VALUES (%d, %s, 1, 'SL', '%s', %d, %d, '%s', 'CNF', 1, 100000, 1)
                """
                        .formatted(
                                id,
                                pnr == null ? "NULL" : "'" + pnr + "'",
                                quota,
                                fromSeq,
                                toSeq,
                                status));
    }

    private static void execute(String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static void seedReference() throws SQLException {
        execute(
                "INSERT INTO stations (code, name) VALUES"
                    + " ('NDLS','New Delhi'),('BCT','Mumbai Central')");
        execute(
                "INSERT INTO trains (number, name, origin_station_id, dest_station_id)"
                    + " VALUES ('12951','Rajdhani',1,2)");
        execute(
                "INSERT INTO train_stops (train_id, station_id, seq, distance_km)"
                    + " VALUES (1,1,0,0.00),(1,2,1,730.00)");
        execute(
                "INSERT INTO coaches (train_id, code, travel_class, berth_count)"
                    + " VALUES (1,'S1','SL',72)");
        execute(
                "INSERT INTO berths (coach_id, ordinal, berth_type)"
                    + " SELECT 1, g, 'LOWER' FROM generate_series(0, 71) g");
        execute(
                "INSERT INTO schedules (train_id, journey_date, status, departure_at)"
                    + " VALUES (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30')");
        // TATKAL is deliberately tiny, so INV-4 can be tripped with three bookings
        // rather than seventy-three.
        execute(
                "INSERT INTO quota_pools (schedule_id, travel_class, quota_type, total_berths)"
                    + " VALUES (1,'SL','GENERAL',72),(1,'SL','TATKAL',2)");
        execute(
                "INSERT INTO users (external_ref)"
                    + " SELECT 'user-' || g FROM generate_series(1, 10) g");
    }
}
