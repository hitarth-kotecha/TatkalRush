package io.tatkalrush.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.IdempotencyStore.Claim;
import java.sql.Connection;
import io.tatkalrush.domain.booking.Pnr;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * <b>T-5 — the same {@code Idempotency-Key} sent 100 times concurrently produces
 * exactly one allocation</b> (§18.1, FR-19).
 *
 * <p>§18.1 specifies the mechanism rather than leaving it open, and says why:
 * written as check-then-act this test is "intermittently flaky in a way that
 * reads as a load-test artifact". The mechanism is insert-first — the key row
 * goes in under its primary key, inside the transaction, <em>before</em> anything
 * is allocated.
 *
 * <p>What makes that correct is Postgres, not the application. Concurrent inserts
 * of one primary key <b>block</b> on the unique index; all but one wait for the
 * incumbent transaction to resolve. So the losers observe the winner's committed
 * {@code booking_id} rather than racing it. There is no lock and no retry loop in
 * the Java.
 *
 * <p>Run against real PostgreSQL because that blocking behaviour <em>is</em> the
 * mechanism. Any in-memory substitute would be testing a fake of the only thing
 * that matters.
 */
class IdempotencyRaceTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    private static String jdbcUrl;

    @BeforeAll
    static void startAndMigrate() {
        POSTGRES.start();
        jdbcUrl = POSTGRES.getJdbcUrl();

        Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static Connection open() throws SQLException {
        var connection =
                DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }

    @BeforeEach
    void reset() throws SQLException {
        try (Connection c = open();
                Statement st = c.createStatement()) {
            st.execute("TRUNCATE idempotency_keys, bookings, users RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO users (external_ref) VALUES ('user-000001')");
            seedScheduleGraph(st);
            c.commit();
        }
    }

    /** Minimal referential scaffolding so bookings can be inserted. */
    private static void seedScheduleGraph(Statement st) throws SQLException {
        st.execute(
                "INSERT INTO stations (code, name) VALUES ('NDLS','New Delhi'),('BCT','Mumbai')"
                        + " ON CONFLICT DO NOTHING");
        st.execute(
                "INSERT INTO trains (number, name, origin_station_id, dest_station_id)"
                        + " VALUES ('12951','Rajdhani',1,2) ON CONFLICT DO NOTHING");
        st.execute(
                "INSERT INTO schedules (train_id, journey_date, status, departure_at)"
                        + " VALUES (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30')"
                        + " ON CONFLICT DO NOTHING");
    }

    /**
     * Stands in for the allocator. Counting calls is the point: T-5 asserts
     * exactly one <em>allocation</em>, not merely one booking row, and only a
     * counter can tell those apart.
     */
    private static long allocateAndCreateBooking(Connection connection, AtomicInteger allocations)
            throws SQLException {
        int attempt = allocations.incrementAndGet();

        // A DISTINCT PNR per allocation, deliberately.
        //
        // An earlier version hardcoded one. That still failed under a
        // check-then-act mutation - but on "duplicate key value violates unique
        // constraint bookings_pnr_key", which sends a reader after the PNR
        // generator instead of the race. A test's failure message is part of the
        // test: this way the assertion below reports the allocation count, which
        // is what T-5 is actually about.
        String pnr = Pnr.fromSequence(attempt).value();

        try (PreparedStatement insert =
                connection.prepareStatement(
                        """
                        INSERT INTO bookings (pnr, schedule_id, travel_class, quota_type,
                            from_seq, to_seq, status, booking_class, passenger_count,
                            fare_paise, user_id)
                        VALUES (?, 1, 'SL', 'TATKAL', 0, 4, 'HELD', 'CNF', 1, 212050, 1)
                        RETURNING id
                        """)) {
            insert.setString(1, pnr);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ------------------------------------------------------------------ T-5

    @Test
    @DisplayName("T-5: 100 concurrent requests with one key produce exactly one allocation")
    void oneKeyOneAllocation() throws Exception {
        final int callers = 100;
        final String key = "idem-key-shared";
        final String requestHash = "hash-of-the-one-request";

        var allocations = new AtomicInteger();
        var ready = new CountDownLatch(callers);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(callers);

        var outcomes = new ConcurrentLinkedQueue<Claim>();
        var failures = new ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < callers; i++) {
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (Connection connection = open()) {
                                    ready.countDown();
                                    go.await();

                                    // The claim and the booking commit TOGETHER.
                                    // Committing the claim early would release the
                                    // waiting callers before a booking exists, and
                                    // they would read a Pending that never resolves.
                                    Claim claim =
                                            JdbcIdempotencyStore.claimOn(
                                                    connection, key, 1L, requestHash);

                                    if (claim instanceof Claim.Won) {
                                        long bookingId =
                                                allocateAndCreateBooking(connection, allocations);
                                        JdbcIdempotencyStore.completeOn(
                                                connection, key, bookingId);
                                    }
                                    connection.commit();
                                    outcomes.add(claim);
                                } catch (Throwable t) {
                                    failures.add(t);
                                } finally {
                                    done.countDown();
                                }
                            });
        }

        assertTrue(ready.await(30, TimeUnit.SECONDS), "callers failed to start");
        go.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "the race did not finish in time");

        if (!failures.isEmpty()) {
            throw new AssertionError(
                    failures.size() + " caller(s) threw; first: " + failures.peek(),
                    failures.peek());
        }

        List<Claim> results = List.copyOf(outcomes);
        long won = results.stream().filter(c -> c instanceof Claim.Won).count();
        long duplicates = results.stream().filter(c -> c instanceof Claim.Duplicate).count();
        long pending = results.stream().filter(c -> c instanceof Claim.Pending).count();

        assertEquals(callers, results.size(), "every caller must reach an outcome");

        // The three assertions T-5 exists for.
        assertEquals(
                1,
                allocations.get(),
                () ->
                        "EXACTLY ONE allocation may happen for one idempotency key. Got "
                                + allocations.get()
                                + ", which is a client retry allocating a second set of berths"
                                + " and orphaning the first for a full TTL.");
        assertEquals(1, won, "exactly one caller may win the key");
        assertEquals(
                callers - 1,
                duplicates,
                () ->
                        "every loser must resolve to the winner's booking. "
                                + pending
                                + " saw Pending, which means a claim was committed before its"
                                + " booking existed.");

        // And they must all point at the SAME booking. Distinct ids here would
        // mean two bookings were created and the key merely hid one of them.
        var bookingIds =
                results.stream()
                        .filter(c -> c instanceof Claim.Duplicate)
                        .map(c -> ((Claim.Duplicate) c).bookingId())
                        .distinct()
                        .toList();
        assertEquals(1, bookingIds.size(), () -> "losers resolved to " + bookingIds);

        assertEquals(1, countBookings(), "exactly one booking row may exist");
    }

    @Test
    @DisplayName("FR-19: the same key with a different body is a 409, not a replay")
    void reusedKeyWithDifferentBodyIsRejected() throws Exception {
        String key = "idem-key-reused";

        try (Connection connection = open()) {
            assertInstanceOf(
                    Claim.Won.class, JdbcIdempotencyStore.claimOn(connection, key, 1L, "hash-A"));
            long bookingId = allocateAndCreateBooking(connection, new AtomicInteger());
            JdbcIdempotencyStore.completeOn(connection, key, bookingId);
            connection.commit();
        }

        try (Connection connection = open()) {
            // A client bug, not a retry. Answering it with the first request's
            // booking would silently confirm a different journey than the one
            // asked for.
            var claim = JdbcIdempotencyStore.claimOn(connection, key, 1L, "hash-B");
            var reused = assertInstanceOf(Claim.Reused.class, claim);
            assertEquals("hash-A", reused.existingRequestHash());
            connection.commit();
        }
    }

    @Test
    @DisplayName("a replayed key returns the same booking, however many times")
    void replayIsStable() throws Exception {
        String key = "idem-key-replayed";
        long originalBookingId;

        try (Connection connection = open()) {
            JdbcIdempotencyStore.claimOn(connection, key, 1L, "hash");
            originalBookingId = allocateAndCreateBooking(connection, new AtomicInteger());
            JdbcIdempotencyStore.completeOn(connection, key, originalBookingId);
            connection.commit();
        }

        for (int i = 0; i < 5; i++) {
            try (Connection connection = open()) {
                var claim = JdbcIdempotencyStore.claimOn(connection, key, 1L, "hash");
                assertEquals(
                        originalBookingId,
                        assertInstanceOf(Claim.Duplicate.class, claim).bookingId());
                connection.commit();
            }
        }
        assertEquals(1, countBookings());
    }

    @Test
    @DisplayName("a winner that rolls back releases the key rather than wedging it")
    void rollbackReleasesTheKey() throws Exception {
        String key = "idem-key-rolled-back";

        try (Connection connection = open()) {
            assertInstanceOf(
                    Claim.Won.class, JdbcIdempotencyStore.claimOn(connection, key, 1L, "hash"));
            // Allocation failed, say SEAT_UNAVAILABLE. Rolling back must leave no
            // trace: a claimed key with no booking behind it would block every
            // retry of that request forever.
            connection.rollback();
        }

        try (Connection connection = open()) {
            assertInstanceOf(
                    Claim.Won.class,
                    JdbcIdempotencyStore.claimOn(connection, key, 1L, "hash"),
                    "after a rollback the key must be claimable again");
            connection.rollback();
        }
    }

    private int countBookings() throws SQLException {
        try (Connection c = open();
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT count(*) FROM bookings")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
