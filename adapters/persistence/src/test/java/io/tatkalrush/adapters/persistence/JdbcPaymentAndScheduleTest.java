package io.tatkalrush.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.PaymentRepository.LedgerEntryType;
import io.tatkalrush.application.ports.PaymentRepository.NewPayment;
import io.tatkalrush.application.ports.PaymentRepository.PaymentStatus;
import io.tatkalrush.domain.booking.Passenger;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import io.tatkalrush.domain.pricing.RefundReason;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * The payment and schedule adapters, against real PostgreSQL.
 *
 * <p>Three things here could not be checked by reading: that {@code ?::jsonb} is
 * required at all, that a cumulative-distance subtraction produces the exact
 * {@code BigDecimal} FR-67 needs, and that {@code max(seq)} is the segment count
 * rather than one more or one less than it.
 */
class JdbcPaymentAndScheduleTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("tatkal")
                    .withUsername("tatkal")
                    .withPassword("tatkal");

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final SegmentRange RANGE = new SegmentRange(0, 4);
    private static final long FARE = 145_000L;

    private static DataSource dataSource;
    private static Connection admin;

    private PoolKey pool;
    private JdbcPaymentRepository payments;
    private JdbcScheduleQuery schedules;
    private JdbcBookingRepository bookings;
    private SpringUnitOfWork unitOfWork;

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
            st.execute(
                    "TRUNCATE ledger_entries, refunds, payment_events, payments,"
                        + " seat_allocations, passengers, bookings RESTART IDENTITY CASCADE");
        }
        pool = new PoolKey(1L, TravelClass.SL, QuotaType.GENERAL);
        payments = new JdbcPaymentRepository(dataSource);
        schedules = new JdbcScheduleQuery(dataSource);
        bookings = new JdbcBookingRepository(dataSource);
        unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource));
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ScheduleQuery: what the hold path needs to know")
    class Schedules {

        @Test
        void findPoolReturnsTheShapeAndTimingOfOnePool() {
            var descriptor = schedules.findPool(pool).orElseThrow();

            assertEquals(72, descriptor.berthCount());
            assertEquals(LocalDate.of(2026, 10, 1), descriptor.journeyDate());
            assertFalse(descriptor.chartPrepared());
        }

        /**
         * {@code max(seq)} is the segment count, not the stop count.
         *
         * <p>Five stops at seq 0..4 means four segments, 0..3, and a journey may
         * run {@code [0,4)}. One more and {@code HoldSeats} would admit a booking
         * occupying a leg the train does not run — which {@code SegmentMask} sets
         * without complaint, because a {@code long} has 64 bits whatever the route
         * is.
         */
        @Test
        void segmentCountIsMaxSeqSoTheWholeRouteIsBookableAndNoMore() {
            var descriptor = schedules.findPool(pool).orElseThrow();

            assertEquals(4, descriptor.segmentCount(), "five stops, four segments");
            assertTrue(RANGE.toSeq() <= descriptor.segmentCount(), "[0,4) must be admissible");
        }

        @Test
        void aGeneralAndATatkalPoolOverTheSameBerthsAreDistinct() {
            var general = schedules.findPool(pool).orElseThrow();
            var tatkal =
                    schedules
                            .findPool(new PoolKey(1L, TravelClass.SL, QuotaType.TATKAL))
                            .orElseThrow();

            assertEquals(72, general.berthCount());
            assertEquals(8, tatkal.berthCount(), "FR-9 sizes TATKAL as ceil(0.10 x cap)");
        }

        @Test
        void anUnknownPoolIsEmptyNotAnError() {
            assertTrue(
                    schedules.findPool(new PoolKey(999L, TravelClass.AC1, QuotaType.GENERAL))
                            .isEmpty());
        }

        @Test
        void chartPreparedIsReadFromTheScheduleStatus() throws SQLException {
            try (Statement st = admin.createStatement()) {
                st.execute(
                        "UPDATE schedules SET status = 'CHARTED', chart_prepared_at = now()"
                            + " WHERE id = 1");
            }
            try {
                assertTrue(schedules.findPool(pool).orElseThrow().chartPrepared());
            } finally {
                try (Statement st = admin.createStatement()) {
                    st.execute(
                            "UPDATE schedules SET status = 'OPEN', chart_prepared_at = NULL"
                                + " WHERE id = 1");
                }
            }
        }

        /** The distance is a subtraction over cumulative readings, and it is exact. */
        @Test
        void distanceIsTheDifferenceBetweenTwoCumulativeReadings() {
            // Stops at 0.00, 180.50, 410.25, 610.75, 730.00 km.
            assertEquals(0, new BigDecimal("730.00").compareTo(schedules.distanceKm(1L, RANGE)));
            assertEquals(
                    0,
                    new BigDecimal("229.75")
                            .compareTo(schedules.distanceKm(1L, new SegmentRange(1, 2))),
                    "410.25 - 180.50, exactly");
        }

        @Test
        void theDistanceStaysABigDecimalWithItsScale() {
            // [0,1) is ONE segment: stop 0 to stop 1. Half-open ranges count
            // segments, not stops, so [0,2) would span stops 0 to 2 and read
            // 410.25 - a distinction worth getting wrong once in a test rather
            // than once in a fare.
            var distance = schedules.distanceKm(1L, new SegmentRange(0, 1));

            // NUMERIC(7,2) round-trips as a scaled BigDecimal. FR-67 feeds this
            // into a ceil; a double would have made 180.50 into 180.49999...
            assertEquals(new BigDecimal("180.50"), distance);
            assertEquals(2, distance.scale());
        }

        @Test
        void aRangeBeyondTheRouteIsRefusedRatherThanPricedAtZero() {
            var thrown =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> schedules.distanceKm(1L, new SegmentRange(0, 9)));

            assertTrue(thrown.getMessage().contains("seq 0..9"), thrown.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("PaymentRepository: the intent, and settling it once")
    class Payments {

        @Test
        void aPaymentRoundTrips() {
            long bookingId = heldBooking();
            long id = unitOfWork.inTransaction(() -> payments.create(newPayment(bookingId, "ref-1")));

            var record = payments.findByReference("ref-1").orElseThrow();

            assertEquals(id, record.id());
            assertEquals(PaymentStatus.INITIATED, record.status());
            assertEquals(FARE, record.amountPaise());
            assertTrue(record.settledAt().isEmpty());
        }

        @Test
        void findCapturedForIgnoresAnIntentThatNeverSettled() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(() -> payments.create(newPayment(bookingId, "ref-1")));

            assertTrue(
                    payments.findCapturedFor(bookingId).isEmpty(),
                    "refunding against an intent sends money that was never taken");

            unitOfWork.inTransaction(
                    () -> payments.settle("ref-1", PaymentStatus.SUCCESS, NOW));

            assertTrue(payments.findCapturedFor(bookingId).isPresent());
        }

        /** DD-034's second layer: exactly-once across routes. */
        @Test
        void onlyTheFirstSettlementWins() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(() -> payments.create(newPayment(bookingId, "ref-1")));

            assertTrue(
                    unitOfWork.inTransaction(
                            () -> payments.settle("ref-1", PaymentStatus.SUCCESS, NOW)));
            assertFalse(
                    unitOfWork.inTransaction(
                            () -> payments.settle("ref-1", PaymentStatus.FAILED, NOW)),
                    "a late FAILED must not overwrite a SUCCESS");

            assertEquals(
                    PaymentStatus.SUCCESS, payments.findByReference("ref-1").orElseThrow().status());
        }

        /**
         * The binding that fails silently in review and loudly at runtime.
         *
         * <p>{@code payload} is JSONB. Bound as a plain string this raises
         * "column is of type jsonb but expression is of type character varying" —
         * an error naming the column, which sends a reader to the schema rather
         * than to the cast that is missing.
         */
        @Test
        void aWebhookPayloadIsStoredAsJsonb() throws SQLException {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(() -> payments.create(newPayment(bookingId, "ref-1")));

            unitOfWork.inTransaction(
                    () ->
                            payments.recordEvent(
                                    "ref-1", "PAYMENT_SUCCEEDED", "{\"status\":\"succeeded\"}"));

            try (Statement st = admin.createStatement();
                    var rs =
                            st.executeQuery(
                                    "SELECT payload->>'status' FROM payment_events"
                                        + " WHERE psp_payment_id = 'ref-1'")) {
                assertTrue(rs.next());
                assertEquals(
                        "succeeded",
                        rs.getString(1),
                        "stored as text rather than jsonb, so the -> operator finds nothing");
            }
        }

        /** T-C5, and FR-55 double-delivers 5% of webhooks deliberately. */
        @Test
        void aRedeliveredEventIsRejectedByTheUniqueKey() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(() -> payments.create(newPayment(bookingId, "ref-1")));

            assertTrue(
                    unitOfWork.inTransaction(
                            () -> payments.recordEvent("ref-1", "PAYMENT_SUCCEEDED", "{}")));
            assertFalse(
                    unitOfWork.inTransaction(
                            () -> payments.recordEvent("ref-1", "PAYMENT_SUCCEEDED", "{}")),
                    "ON CONFLICT DO NOTHING returns zero rows rather than raising");
        }

        @Test
        void differentEventTypesForOnePaymentAreBothRecorded() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(() -> payments.create(newPayment(bookingId, "ref-1")));

            assertTrue(
                    unitOfWork.inTransaction(
                            () -> payments.recordEvent("ref-1", "PAYMENT_SUCCEEDED", "{}")));
            assertTrue(
                    unitOfWork.inTransaction(
                            () -> payments.recordEvent("ref-1", "PAYMENT_FAILED", "{}")),
                    "the key is (payment, type); an out-of-order FAILED is still audit");
        }

        /**
         * The conflicting insert must not poison the transaction, or the
         * settlement that follows it in the same transaction cannot run.
         */
        @Test
        void aRejectedDuplicateLeavesTheTransactionUsable() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(() -> payments.create(newPayment(bookingId, "ref-1")));
            unitOfWork.inTransaction(() -> payments.recordEvent("ref-1", "PAYMENT_SUCCEEDED", "{}"));

            boolean settledAfterDuplicate =
                    unitOfWork.inTransaction(
                            () -> {
                                payments.recordEvent("ref-1", "PAYMENT_SUCCEEDED", "{}");
                                return payments.settle("ref-1", PaymentStatus.SUCCESS, NOW);
                            });

            assertTrue(
                    settledAfterDuplicate,
                    "ON CONFLICT returns; catching a unique violation would have aborted "
                            + "the transaction before this statement could run");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-23's sweep selects by state")
    class Sweeping {

        @Test
        void aBookingStuckInPaymentPendingIsFound() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(
                    () -> {
                        payments.create(newPayment(bookingId, "ref-1"));
                        return bookings.beginPayment(bookingId, NOW);
                    });

            var pending = payments.findPendingSettlements(Instant.now().plusSeconds(1), 100);

            assertEquals(1, pending.size());
            assertEquals(bookingId, pending.getFirst().bookingId());
        }

        @Test
        void aBookingThatMovedOnIsNotFound() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(
                    () -> {
                        payments.create(newPayment(bookingId, "ref-1"));
                        return bookings.beginPayment(bookingId, NOW);
                    });
            unitOfWork.inTransaction(() -> bookings.markFailed(bookingId, NOW));

            assertTrue(payments.findPendingSettlements(Instant.now().plusSeconds(1), 100).isEmpty());
        }

        /**
         * The crash case: payment SUCCESS, event already recorded, booking never
         * advanced. An event-driven sweep would skip it; this one must not.
         */
        @Test
        void aSettledPaymentWhoseBookingNeverAdvancedIsStillFound() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(
                    () -> {
                        payments.create(newPayment(bookingId, "ref-1"));
                        return bookings.beginPayment(bookingId, NOW);
                    });
            unitOfWork.inTransaction(
                    () -> {
                        payments.recordEvent("ref-1", "PAYMENT_SUCCEEDED", "{}");
                        return payments.settle("ref-1", PaymentStatus.SUCCESS, NOW);
                    });

            var pending = payments.findPendingSettlements(Instant.now().plusSeconds(1), 100);

            assertEquals(1, pending.size(), "INV-3's repair depends on finding this");
            assertEquals(PaymentStatus.SUCCESS, pending.getFirst().payment().status());
        }

        @Test
        void aPaymentYoungerThanTheCutoffIsLeftAlone() {
            long bookingId = heldBooking();
            unitOfWork.inTransaction(
                    () -> {
                        payments.create(newPayment(bookingId, "ref-1"));
                        return bookings.beginPayment(bookingId, NOW);
                    });

            // FR-53's median is 800 ms; sweeping payments from the last minute
            // would poll for answers that are simply still in flight.
            assertTrue(
                    payments.findPendingSettlements(Instant.now().minusSeconds(60), 100).isEmpty());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("refunds and the ledger")
    class Refunds {

        @Test
        void aCompletedRefundMovesThePaymentAndBalancesTheLedger() {
            long bookingId = heldBooking();
            long paymentId =
                    unitOfWork.inTransaction(
                            () -> {
                                long id = payments.create(newPayment(bookingId, "ref-1"));
                                payments.settle("ref-1", PaymentStatus.SUCCESS, NOW);
                                payments.recordLedgerEntry(
                                        bookingId, LedgerEntryType.CHARGE, FARE);
                                return id;
                            });

            long refundId =
                    unitOfWork.inTransaction(
                            () ->
                                    payments.openRefund(
                                            bookingId, paymentId, FARE, RefundReason.HOLD_EXPIRED));
            unitOfWork.inTransaction(
                    () -> {
                        payments.completeRefund(refundId, paymentId, bookingId, FARE);
                        return null;
                    });

            assertEquals("COMPLETED", refundStatus(refundId));
            assertEquals(
                    PaymentStatus.REFUNDED, payments.findByReference("ref-1").orElseThrow().status());
            assertEquals(
                    List.of("CHARGE", "REFUND"),
                    ledgerTypes(bookingId),
                    "INV-2 balances a capture against its return; one without the other "
                            + "is what a misplaced entry looks like");
        }

        @Test
        void aFailedRefundLeavesThePaymentCaptured() {
            long bookingId = heldBooking();
            long paymentId =
                    unitOfWork.inTransaction(
                            () -> {
                                long id = payments.create(newPayment(bookingId, "ref-1"));
                                payments.settle("ref-1", PaymentStatus.SUCCESS, NOW);
                                return id;
                            });
            long refundId =
                    unitOfWork.inTransaction(
                            () ->
                                    payments.openRefund(
                                            bookingId, paymentId, FARE, RefundReason.HOLD_EXPIRED));

            unitOfWork.inTransaction(
                    () -> {
                        payments.failRefund(refundId);
                        return null;
                    });

            assertEquals("FAILED", refundStatus(refundId));
            assertEquals(
                    PaymentStatus.SUCCESS,
                    payments.findByReference("ref-1").orElseThrow().status(),
                    "the money is still with the PSP; REFUNDED would assert otherwise");
            assertTrue(ledgerTypes(bookingId).isEmpty(), "no movement, no entry");
        }

        @Test
        void anAllocationConflictRefundIsFindableByReasonAlone() {
            long bookingId = heldBooking();
            long paymentId =
                    unitOfWork.inTransaction(
                            () -> {
                                long id = payments.create(newPayment(bookingId, "ref-1"));
                                payments.settle("ref-1", PaymentStatus.SUCCESS, NOW);
                                return id;
                            });

            unitOfWork.inTransaction(
                    () ->
                            payments.openRefund(
                                    bookingId, paymentId, FARE, RefundReason.ALLOCATION_CONFLICT));

            // INV-11 asks exactly this question after every run, and a single row
            // fails it.
            assertEquals(1, countRefundsWithReason("ALLOCATION_CONFLICT"));
            assertEquals(0, countRefundsWithReason("HOLD_EXPIRED"));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private NewPayment newPayment(long bookingId, String reference) {
        return new NewPayment(bookingId, reference, FARE);
    }

    private long heldBooking() {
        return unitOfWork.inTransaction(
                () ->
                        bookings.createHeld(
                                new BookingRepository.NewHeldBooking(
                                        pool,
                                        RANGE,
                                        List.of(new Passenger("Traveller", 30, Passenger.Gender.O)),
                                        FARE,
                                        1L,
                                        NOW.plusSeconds(120),
                                        "key-" + java.util.UUID.randomUUID(),
                                        List.of(1L))));
    }

    private String refundStatus(long refundId) {
        return queryString("SELECT status FROM refunds WHERE id = " + refundId);
    }

    private List<String> ledgerTypes(long bookingId) {
        try (Statement st = admin.createStatement();
                var rs =
                        st.executeQuery(
                                "SELECT entry_type FROM ledger_entries WHERE booking_id = "
                                        + bookingId
                                        + " ORDER BY id")) {
            var types = new java.util.ArrayList<String>();
            while (rs.next()) {
                types.add(rs.getString(1));
            }
            return types;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int countRefundsWithReason(String reason) {
        return Integer.parseInt(
                queryString("SELECT count(*) FROM refunds WHERE reason = '" + reason + "'"));
    }

    private String queryString(String sql) {
        try (Statement st = admin.createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void seedReferenceData() throws SQLException {
        try (Statement st = admin.createStatement()) {
            st.execute(
                    "INSERT INTO stations (code, name) VALUES"
                        + " ('NDLS','New Delhi'),('MTJ','Mathura'),('KOTA','Kota'),"
                        + " ('RTM','Ratlam'),('BCT','Mumbai Central')");
            st.execute(
                    "INSERT INTO trains (number, name, origin_station_id, dest_station_id)"
                        + " VALUES ('12951','Rajdhani',1,5)");
            // Five stops, seq 0..4 - so four segments, and max(seq) is 4.
            // Cumulative distances, deliberately not round numbers: the .25/.75
            // values are what would drift if this path ever touched a double.
            st.execute(
                    "INSERT INTO train_stops (train_id, station_id, seq, distance_km) VALUES"
                        + " (1,1,0,0.00),(1,2,1,180.50),(1,3,2,410.25),"
                        + " (1,4,3,610.75),(1,5,4,730.00)");
            st.execute(
                    "INSERT INTO coaches (train_id, code, travel_class, berth_count)"
                        + " VALUES (1,'S1','SL',72)");
            st.execute(
                    "INSERT INTO berths (coach_id, ordinal, berth_type)"
                        + " SELECT 1, g, 'LOWER' FROM generate_series(0, 71) g");
            st.execute(
                    "INSERT INTO schedules (train_id, journey_date, status, departure_at)"
                        + " VALUES (1,'2026-10-01','OPEN','2026-10-01 16:55+05:30')");
            // FR-9: TATKAL is ceil(0.10 x capacity), minimum 1. 72 -> 8.
            st.execute(
                    "INSERT INTO quota_pools (schedule_id, travel_class, quota_type, total_berths)"
                        + " VALUES (1,'SL','GENERAL',72),(1,'SL','TATKAL',8)");
            st.execute(
                    "INSERT INTO users (external_ref)"
                        + " SELECT 'user-' || g FROM generate_series(1, 10) g");
        }
    }
}
