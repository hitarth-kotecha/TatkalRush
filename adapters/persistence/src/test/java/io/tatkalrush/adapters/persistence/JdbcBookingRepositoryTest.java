package io.tatkalrush.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.BookingRepository.AllocationOutcome;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.booking.Passenger;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The port contracts, executed.
 *
 * <p>{@code BookingRepository} and {@code UnitOfWork} make three claims about
 * Postgres specifically — that {@code FOR UPDATE} serialises the settlement
 * routes, that a {@code SAVEPOINT} keeps a transaction usable after a constraint
 * violation, and that a rollback releases an idempotency claim. Every one of them
 * was prose until this class ran, and the third turned out to be false: the port
 * committed on normal return, and only a test fake inventing its own rollback rule
 * made {@code SEAT_UNAVAILABLE} appear to release its key.
 *
 * <p>Against real PostgreSQL, not an in-memory substitute. H2 has no
 * {@code btree_gist}, no {@code INT4RANGE} and no {@code EXCLUDE}, so it could not
 * express the one behaviour worth checking here.
 */
class JdbcBookingRepositoryTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final SegmentRange RANGE = new SegmentRange(0, 4);

    private static DataSource dataSource;
    private static Connection admin;

    private PoolKey pool;
    private JdbcBookingRepository bookings;
    private SpringUnitOfWork unitOfWork;
    private JdbcPnrSequence pnrSequence;

    @BeforeAll
    static void startAndMigrate() throws SQLException {
        POSTGRES.start();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;

        admin =
                DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        seedReferenceData();
    }

    @AfterAll
    static void stop() throws SQLException {
        if (admin != null) {
            admin.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() throws SQLException {
        try (Statement st = admin.createStatement()) {
            st.execute("TRUNCATE seat_allocations, passengers, bookings RESTART IDENTITY CASCADE");
        }
        pool = new PoolKey(1L, TravelClass.SL, QuotaType.GENERAL);
        bookings = new JdbcBookingRepository(dataSource);
        unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource));
        pnrSequence = new JdbcPnrSequence(dataSource);
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("a held booking round-trips, berths and all")
    class Holding {

        @Test
        void createHeldStoresPassengersPairedWithBerths() {
            long id = held(List.of(1L, 2L));

            var view = bookings.findById(id).orElseThrow();

            assertEquals(BookingStatus.HELD, view.status());
            assertEquals(List.of(1L, 2L), view.berthIds());
            assertEquals(2, view.passengerCount());
            assertEquals(pool, view.pool());
            assertEquals(RANGE, view.range());
            assertTrue(view.pnr().isEmpty(), "§6.4 issues the PNR at confirmation");
        }

        @Test
        void berthOrderIsStableAcrossReads() {
            // FR-50 needs two runs of the same booking to produce identical rows.
            // Without the ORDER BY, Postgres may return passengers in any order.
            long id = held(List.of(5L, 3L, 1L));

            assertEquals(bookings.findById(id).orElseThrow().berthIds(), List.of(5L, 3L, 1L));
        }

        @Test
        void countActiveHoldsIgnoresLapsedOnes() {
            held(List.of(1L));
            heldUntil(List.of(2L), NOW.minusSeconds(1));

            assertEquals(1, bookings.countActiveHolds(7L, NOW), "FR-20 counts live holds only");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("the SAVEPOINT contract")
    class Savepoints {

        @Test
        void allocationsPersistForALiveHold() {
            long id = held(List.of(1L, 2L));

            var outcome =
                    unitOfWork.inTransaction(
                            () -> bookings.persistAllocations(id, 1L, RANGE, List.of(1L, 2L)));

            assertInstanceOf(AllocationOutcome.Persisted.class, outcome);
            assertEquals(2, allocationCount());
        }

        /**
         * The contract that could not be verified by reading the code.
         *
         * <p>Postgres aborts the entire transaction on a failed statement — every
         * later statement answers {@code 25P02} until rollback. So the conflict
         * must be returned <em>and</em> the transaction must still be usable, or
         * {@code ConfirmBooking} cannot write the refund row it is about to decide
         * on. Both halves are asserted here.
         */
        @Test
        void aConflictIsReturnedAndLeavesTheTransactionUsable() {
            long first = held(List.of(1L));
            long second = held(List.of(1L));

            unitOfWork.inTransaction(
                    () -> bookings.persistAllocations(first, 1L, RANGE, List.of(1L)));

            var stillWorks = new AtomicBoolean(false);
            var outcome =
                    unitOfWork.inTransaction(
                            () -> {
                                var result =
                                        bookings.persistAllocations(
                                                second, 1L, RANGE, List.of(1L));
                                // If the savepoint were missing, this read would
                                // fail with 25P02 instead of returning a count.
                                stillWorks.set(bookings.findById(second).isPresent());
                                return result;
                            });

            var conflict = assertInstanceOf(AllocationOutcome.Conflict.class, outcome);
            assertEquals(1L, conflict.berthId(), "the refused berth, found by query");
            assertTrue(
                    stillWorks.get(),
                    "the transaction must survive the violation, or the refund cannot be written");
            assertEquals(1, allocationCount(), "the conflicting rows must not be there");
        }

        @Test
        void theConflictingBerthIsNamedEvenWhenOnlyOneOfSeveralOverlaps() {
            long first = held(List.of(9L));
            long second = held(List.of(7L, 8L, 9L));

            unitOfWork.inTransaction(
                    () -> bookings.persistAllocations(first, 1L, RANGE, List.of(9L)));

            var outcome =
                    unitOfWork.inTransaction(
                            () ->
                                    bookings.persistAllocations(
                                            second, 1L, RANGE, List.of(7L, 8L, 9L)));

            assertEquals(
                    9L,
                    assertInstanceOf(AllocationOutcome.Conflict.class, outcome).berthId(),
                    "INV-11's report must name the berth that was actually double-sold");
        }

        @Test
        void aBookingsOwnRowsAreReportedAsAlreadyPresentNotAsAConflict() {
            long id = held(List.of(1L));
            unitOfWork.inTransaction(() -> bookings.persistAllocations(id, 1L, RANGE, List.of(1L)));

            var outcome =
                    unitOfWork.inTransaction(
                            () -> bookings.persistAllocations(id, 1L, RANGE, List.of(1L)));

            // Without the pre-insert check this would be Conflict, and a duplicate
            // confirmation would be reported as an allocator defect that fails the
            // run (DD-033).
            assertInstanceOf(AllocationOutcome.AlreadyPresent.class, outcome);
        }

        @Test
        void complementaryLegsShareABerth() {
            long delhiToRatlam = held(List.of(1L));
            long ratlamToMumbai = held(List.of(1L));

            unitOfWork.inTransaction(
                    () ->
                            bookings.persistAllocations(
                                    delhiToRatlam, 1L, new SegmentRange(0, 2), List.of(1L)));
            var second =
                    unitOfWork.inTransaction(
                            () ->
                                    bookings.persistAllocations(
                                            ratlamToMumbai, 1L, new SegmentRange(2, 4), List.of(1L)));

            assertInstanceOf(
                    AllocationOutcome.Persisted.class,
                    second,
                    "T-3: half-open ranges meeting at a stop share a leg with nothing");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("transitions are compare-and-set, not read-then-write")
    class Transitions {

        @Test
        void onlyOneCallerWinsATransition() {
            long id = held(List.of(1L));

            assertTrue(unitOfWork.inTransaction(() -> bookings.beginPayment(id, NOW)));
            assertFalse(
                    unitOfWork.inTransaction(() -> bookings.beginPayment(id, NOW)),
                    "the second caller must be told it lost, not silently succeed");
        }

        @Test
        void confirmSetsThePnrAndOnlyFromPaymentPending() {
            long id = held(List.of(1L));
            String pnr = io.tatkalrush.domain.booking.Pnr.fromSequence(pnrSequence.next()).value();

            assertFalse(
                    unitOfWork.inTransaction(() -> bookings.confirm(id, pnr, NOW)),
                    "a HELD booking cannot be confirmed; payment has not happened");

            unitOfWork.inTransaction(() -> bookings.beginPayment(id, NOW));
            assertTrue(unitOfWork.inTransaction(() -> bookings.confirm(id, pnr, NOW)));

            var view = bookings.findById(id).orElseThrow();
            assertEquals(BookingStatus.CONFIRMED, view.status());
            assertEquals(java.util.Optional.of(pnr), view.pnr());
        }

        /**
         * The WHERE clause, not the state machine, is what protects the row.
         *
         * <p>{@code markFailed} declares its own transition as
         * {@code PAYMENT_PENDING -> FAILED}, so the {@code requireTransitionTo}
         * guard inside it validates <em>that declaration</em> and can never fire
         * for a particular booking. What stops a HELD booking being failed is
         * {@code WHERE status = 'PAYMENT_PENDING'} matching nothing — and the
         * caller being told it updated no rows rather than assuming it won.
         */
        @Test
        void aBookingInTheWrongStateIsNotTransitionedAndTheCallerIsTold() {
            long id = held(List.of(1L));

            assertFalse(unitOfWork.inTransaction(() -> bookings.markFailed(id, NOW)));
            assertEquals(
                    BookingStatus.HELD,
                    bookings.findById(id).orElseThrow().status(),
                    "the row must be untouched, not merely reported as unchanged");
        }

        @Test
        void thePnrSequenceNeverRepeats() {
            long a = pnrSequence.next();
            long b = pnrSequence.next();

            assertNotEquals(a, b);
            assertEquals(a + 1, b);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("the transaction boundary")
    class Transactions {

        /**
         * The bug this class was written to catch.
         *
         * <p>{@code HoldSeats} returns {@code SEAT_UNAVAILABLE} and relies on the
         * rollback to release its idempotency claim. The port committed on normal
         * return; only a test fake pretended otherwise.
         */
        @Test
        void rollingBackOnAReturnedValueDiscardsTheWork() {
            long id = held(List.of(1L));

            String answer =
                    unitOfWork.inTransaction(
                            () -> {
                                bookings.beginPayment(id, NOW);
                                return "SEAT_UNAVAILABLE";
                            },
                            result -> result.equals("SEAT_UNAVAILABLE"));

            assertEquals("SEAT_UNAVAILABLE", answer, "the caller still gets its answer");
            assertEquals(
                    BookingStatus.HELD,
                    bookings.findById(id).orElseThrow().status(),
                    "and the work is gone — this is what releases the idempotency key");
        }

        @Test
        void normalReturnCommits() {
            long id = held(List.of(1L));

            unitOfWork.inTransaction(() -> bookings.beginPayment(id, NOW));

            assertEquals(
                    BookingStatus.PAYMENT_PENDING, bookings.findById(id).orElseThrow().status());
        }

        @Test
        void anExceptionRollsBack() {
            long id = held(List.of(1L));

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () ->
                            unitOfWork.inTransaction(
                                    () -> {
                                        bookings.beginPayment(id, NOW);
                                        throw new IllegalStateException("boom");
                                    }));

            assertEquals(BookingStatus.HELD, bookings.findById(id).orElseThrow().status());
        }

        /**
         * PROPAGATION_REQUIRED, and why it is not a default worth changing.
         *
         * <p>With {@code REQUIRES_NEW} the inner call would take a second connection
         * and then ask to lock a row the outer transaction already holds — blocking
         * on itself until the pool timed out. This test would hang rather than fail,
         * which is exactly how that bug presents in production.
         */
        @Test
        void aNestedTransactionJoinsTheOuterOneRatherThanDeadlockingOnIt() {
            long id = held(List.of(1L));

            var status =
                    unitOfWork.inTransaction(
                            () -> {
                                bookings.findByIdForUpdate(id);
                                // Same row, same lock, from inside.
                                return unitOfWork.inTransaction(
                                        () -> bookings.findByIdForUpdate(id).orElseThrow().status());
                            });

            assertEquals(BookingStatus.HELD, status);
        }

        /** {@code FOR UPDATE} must actually block, or DD-033's argument is void. */
        @Test
        void forUpdateBlocksASecondReaderUntilTheFirstCommits() throws Exception {
            long id = held(List.of(1L));

            var holderHasLock = new CountDownLatch(1);
            var secondFinished = new CountDownLatch(1);
            var observed = new AtomicLong(-1);

            var holder =
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            unitOfWork.inTransaction(
                                                    () -> {
                                                        bookings.findByIdForUpdate(id);
                                                        holderHasLock.countDown();
                                                        sleep(1200);
                                                        bookings.beginPayment(id, NOW);
                                                        return null;
                                                    }));

            assertTrue(holderHasLock.await(10, TimeUnit.SECONDS), "the first lock was never taken");

            var contender =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        var view =
                                                unitOfWork.inTransaction(
                                                        () ->
                                                                bookings.findByIdForUpdate(id)
                                                                        .orElseThrow());
                                        // If the read had NOT blocked it would have
                                        // seen HELD - the holder's transition was
                                        // still uncommitted when this started.
                                        observed.set(
                                                view.status() == BookingStatus.PAYMENT_PENDING
                                                        ? 1
                                                        : 0);
                                        secondFinished.countDown();
                                    });

            holder.join();
            assertTrue(secondFinished.await(10, TimeUnit.SECONDS), "the second reader never ran");
            contender.join();

            assertEquals(
                    1L,
                    observed.get(),
                    "the second reader saw HELD, so FOR UPDATE did not block and the two "
                            + "settlement routes can race (see DD-033)");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void sleep(long millis) {
        try {
            Thread.sleep(Duration.ofMillis(millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long held(List<Long> berthIds) {
        return heldUntil(berthIds, NOW.plusSeconds(120));
    }

    private long heldUntil(List<Long> berthIds, Instant expiry) {
        var passengers =
                berthIds.stream()
                        .map(b -> new Passenger("Passenger " + b, 30, Passenger.Gender.O))
                        .toList();

        return unitOfWork.inTransaction(
                () ->
                        bookings.createHeld(
                                new BookingRepository.NewHeldBooking(
                                        pool,
                                        RANGE,
                                        passengers,
                                        145_000L,
                                        7L,
                                        expiry,
                                        "key-" + java.util.UUID.randomUUID(),
                                        berthIds)));
    }

    private int allocationCount() {
        try (Statement st = admin.createStatement();
                var rs = st.executeQuery("SELECT count(*) FROM seat_allocations")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Enough reference data for the foreign keys to resolve. */
    private static void seedReferenceData() throws SQLException {
        try (Statement st = admin.createStatement()) {
            st.execute("INSERT INTO stations (code, name) VALUES ('NDLS','New Delhi'),('BCT','Mumbai Central')");
            st.execute(
                    "INSERT INTO trains (number, name, origin_station_id, dest_station_id)"
                        + " VALUES ('12951','Rajdhani',1,2)");
            st.execute(
                    "INSERT INTO coaches (train_id, code, travel_class, berth_count)"
                        + " VALUES (1,'S1','SL',72)");
            st.execute(
                    "INSERT INTO berths (coach_id, ordinal, berth_type)"
                        + " SELECT 1, g, 'LOWER' FROM generate_series(0, 9) g");
            st.execute(
                    "INSERT INTO schedules (train_id, journey_date, status, departure_at)"
                        + " VALUES (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30')");
            // Ten, so the fixture's user_id 7 resolves. FR-69 wants >= 5,000 in
            // the real seed; this is only enough to satisfy the foreign key.
            st.execute(
                    "INSERT INTO users (external_ref)"
                        + " SELECT 'user-' || g FROM generate_series(1, 10) g");
        }
    }
}
