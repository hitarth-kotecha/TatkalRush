package io.tatkalrush.application.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.application.ports.AvailabilitySnapshot;
import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.ConfirmResult;
import io.tatkalrush.application.ports.PaymentGateway;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.ScheduleQuery;
import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.application.usecases.CancelBooking.Outcome;
import io.tatkalrush.application.usecases.CancelBooking.RefundSettlement;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import io.tatkalrush.domain.pricing.RefundReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-43 to FR-46.
 *
 * <p>Two tests carry the weight. {@code aHeldBookingIsReleasedAndNeverRefunded}
 * keeps the two operations apart — FR-27 has no {@code HELD → CANCELLED} edge, and
 * the refund path would return 90% of a fare nobody paid. And
 * {@code theCancellationCommitsBeforeTheBerthsAreFreed} pins the ordering that
 * decides which way a crash between Postgres and Redis breaks.
 */
class CancelBookingTest {

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final Instant DEPARTURE = NOW.plus(Duration.ofDays(5));
    private static final long FARE = 100_000L;
    private static final long USER = 7L;
    private static final List<Long> BERTHS = List.of(11L, 12L);

    private List<String> calls;
    private FakeBookings bookings;
    private FakePayments payments;
    private FakeGateway gateway;
    private RecordingAllocator allocator;
    private FakeSchedules schedules;
    private CancelBooking cancelBooking;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        bookings = new FakeBookings(calls);
        payments = new FakePayments(calls);
        gateway = new FakeGateway(calls);
        allocator = new RecordingAllocator(calls);
        schedules = new FakeSchedules();
        cancelBooking =
                new CancelBooking(
                        bookings, payments, gateway, allocator, schedules,
                        new InlineUnitOfWork(calls));
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-43: a held booking is released, not cancelled")
    class Releasing {

        /**
         * The distinction FR-27 encodes as a missing edge. A held booking has taken
         * no money, so routing it through the refund path would have RefundPolicy
         * return a percentage of a fare nobody paid.
         */
        @Test
        void aHeldBookingIsReleasedAndNeverRefunded() {
            given(BookingStatus.HELD, QuotaType.GENERAL);

            var outcome = cancelBooking.cancel("PNR1", USER, NOW);

            assertInstanceOf(Outcome.Released.class, outcome);
            assertEquals(BookingStatus.EXPIRED, bookings.get(1L).status(), "FR-43: EXPIRED");
            assertTrue(payments.refundsOpened.isEmpty(), "no money moved, so none comes back");
            assertEquals(0, gateway.refunds, "the PSP must not be called at all");
        }

        /** release(), not releaseConfirmed(): the hold record still exists. */
        @Test
        void itUsesTheHoldPathSoTheReaperEntryGoesToo() {
            given(BookingStatus.HELD, QuotaType.GENERAL);

            cancelBooking.cancel("PNR1", USER, NOW);

            assertEquals(List.of("hold-key-1"), allocator.released);
            assertTrue(
                    allocator.confirmedReleases.isEmpty(),
                    "releaseConfirmed leaves the hold in the reaper's ZSET");
        }

        @Test
        void losingToTheReaperIsTheOutcomeTheCallerWanted() {
            given(BookingStatus.HELD, QuotaType.GENERAL);
            bookings.loseTransitions();

            assertInstanceOf(
                    Outcome.AlreadyResolved.class, cancelBooking.cancel("PNR1", USER, NOW));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-44/FR-45: cancelling a confirmed booking")
    class Cancelling {

        @Test
        void moreThan48HoursOutRefunds90Percent() {
            given(BookingStatus.CONFIRMED, QuotaType.GENERAL);

            var cancelled =
                    assertInstanceOf(
                            Outcome.Cancelled.class, cancelBooking.cancel("PNR1", USER, NOW));

            assertEquals(90_000L, cancelled.refundPaise(), "FR-44's EARLY tier");
            assertEquals(RefundSettlement.COMPLETED, cancelled.settlement());
            assertEquals(BookingStatus.CANCELLED, bookings.get(1L).status());
        }

        /** FR-45, checked before the tier — a real IRCTC rule and the interesting one. */
        @Test
        void aConfirmedTatkalBookingGetsNothingBackHoweverEarly() {
            given(BookingStatus.CONFIRMED, QuotaType.TATKAL);

            var cancelled =
                    assertInstanceOf(
                            Outcome.Cancelled.class, cancelBooking.cancel("PNR1", USER, NOW));

            assertEquals(0L, cancelled.refundPaise(), "five days out, and still nothing");
            assertEquals(RefundSettlement.NOT_OWED, cancelled.settlement());
            assertEquals(0, gateway.refunds, "nothing owed, so nothing attempted");
            assertTrue(payments.refundsOpened.isEmpty(), "and no refunds row either");
        }

        @Test
        void underTwelveHoursRefundsNothingButStillCancels() {
            given(BookingStatus.CONFIRMED, QuotaType.GENERAL);
            Instant lateCancel = DEPARTURE.minus(Duration.ofHours(6));

            var cancelled =
                    assertInstanceOf(
                            Outcome.Cancelled.class,
                            cancelBooking.cancel("PNR1", USER, lateCancel));

            assertEquals(0L, cancelled.refundPaise());
            assertEquals(BookingStatus.CANCELLED, bookings.get(1L).status());
            assertEquals(
                    1,
                    allocator.confirmedReleases.size(),
                    "the berth comes back whether or not the money does");
        }

        @Test
        void theRefundIsAPercentageOfTheFrozenFare() {
            given(BookingStatus.CONFIRMED, QuotaType.GENERAL);
            // A rate-table edit must not change what this booking gets back.
            schedules.distanceKm = new BigDecimal("99999.99");

            var cancelled =
                    assertInstanceOf(
                            Outcome.Cancelled.class, cancelBooking.cancel("PNR1", USER, NOW));

            assertEquals(90_000L, cancelled.refundPaise(), "90% of the STORED 100000");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("the ordering across two stores")
    class Ordering {

        /**
         * Freeing the berth first and then failing to commit leaves it available
         * while the booking still says CONFIRMED — and the next buyer's
         * confirmation trips the exclusion constraint, reported as
         * ALLOCATION_CONFLICT, failing the run and accusing the allocator of a bug
         * it did not commit.
         */
        @Test
        void theCancellationCommitsBeforeTheBerthsAreFreed() {
            given(BookingStatus.CONFIRMED, QuotaType.GENERAL);

            cancelBooking.cancel("PNR1", USER, NOW);

            int commit = calls.indexOf("tx.commit");
            int freed = calls.indexOf("allocator.releaseConfirmed");

            assertTrue(
                    commit < freed,
                    "a crash must leave a berth stuck, never a seat sold twice: " + calls);
        }

        /** §13.4 rebuilds Redis from these rows. */
        @Test
        void theAllocationRowsGoInTheSameTransactionAsTheTransition() {
            given(BookingStatus.CONFIRMED, QuotaType.GENERAL);

            cancelBooking.cancel("PNR1", USER, NOW);

            int begin = calls.indexOf("tx.begin");
            int deleted = calls.indexOf("bookings.deleteAllocations");
            int commit = calls.indexOf("tx.commit");

            assertTrue(
                    begin < deleted && deleted < commit,
                    "rows surviving a cancellation get the berth re-occupied by the next "
                            + "rebuild: " + calls);
        }

        @Test
        void theRefundIntentIsWrittenBeforeTheGatewayIsCalled() {
            given(BookingStatus.CONFIRMED, QuotaType.GENERAL);

            cancelBooking.cancel("PNR1", USER, NOW);

            assertTrue(
                    calls.indexOf("payments.openRefund") < calls.indexOf("gateway.refund"),
                    calls.toString());
        }

        @Test
        void anUnreachableGatewayLeavesTheRefundPending() {
            given(BookingStatus.CONFIRMED, QuotaType.GENERAL);
            gateway.unreachable();

            var cancelled =
                    assertInstanceOf(
                            Outcome.Cancelled.class, cancelBooking.cancel("PNR1", USER, NOW));

            assertEquals(RefundSettlement.PENDING, cancelled.settlement());
            assertTrue(payments.refundsClosed.isEmpty(), "left PENDING for a retry");
            assertEquals(
                    BookingStatus.CANCELLED,
                    bookings.get(1L).status(),
                    "the seat is not this passenger's regardless of where the money is");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("who may cancel what")
    class Authorisation {

        /** A different answer would make the endpoint a PNR oracle. */
        @Test
        void anotherUsersBookingIsIndistinguishableFromAMissingOne() {
            given(BookingStatus.CONFIRMED, QuotaType.GENERAL);

            var theirs = cancelBooking.cancel("PNR1", USER + 1, NOW);
            var missing = cancelBooking.cancel("NO-SUCH-PNR", USER, NOW);

            assertInstanceOf(Outcome.UnknownBooking.class, theirs);
            assertInstanceOf(Outcome.UnknownBooking.class, missing);
            assertEquals(BookingStatus.CONFIRMED, bookings.get(1L).status(), "untouched");
        }

        @Test
        void aTerminalBookingCannotBeCancelledAgain() {
            given(BookingStatus.CANCELLED, QuotaType.GENERAL);

            assertEquals(
                    BookingStatus.CANCELLED,
                    assertInstanceOf(
                                    Outcome.NotCancellable.class,
                                    cancelBooking.cancel("PNR1", USER, NOW))
                            .status());
        }

        @Test
        void aFailedBookingHasNoCancellationPath() {
            given(BookingStatus.FAILED, QuotaType.GENERAL);

            assertInstanceOf(
                    Outcome.NotCancellable.class, cancelBooking.cancel("PNR1", USER, NOW));
            assertEquals(0, gateway.refunds);
        }
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private void given(BookingStatus status, QuotaType quota) {
        var pool = new PoolKey(1L, TravelClass.SL, quota);
        bookings.put(
                new BookingRepository.BookingView(
                        1L,
                        Optional.of("PNR1"),
                        status,
                        pool,
                        new SegmentRange(0, 4),
                        2,
                        FARE,
                        USER,
                        Optional.of(NOW.plusSeconds(120)),
                        BERTHS));
        schedules.pool = pool;
        payments.capture(1L, "psp-ref-1", FARE);
    }

    // ── fakes ───────────────────────────────────────────────────────────────

    private record InlineUnitOfWork(List<String> calls) implements UnitOfWork {
        @Override
        public <T> T inTransaction(Supplier<T> work, Predicate<T> rollbackIf) {
            calls.add("tx.begin");
            T result = work.get();
            calls.add(rollbackIf.test(result) ? "tx.rollback" : "tx.commit");
            return result;
        }
    }

    private static final class RecordingAllocator implements SeatAllocator {
        private final List<String> calls;
        final List<String> released = new ArrayList<>();
        final List<String> confirmedReleases = new ArrayList<>();

        RecordingAllocator(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void release(String holdId) {
            calls.add("allocator.release");
            released.add(holdId);
        }

        @Override
        public int releaseConfirmed(PoolKey pool, SegmentRange range, List<Long> berthIds) {
            calls.add("allocator.releaseConfirmed");
            confirmedReleases.add(pool + " " + range + " " + berthIds);
            return berthIds.size();
        }

        @Override
        public AllocationResult allocate(AllocationRequest request) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public ConfirmResult confirm(String holdId, long bookingId) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public AvailabilitySnapshot availability(PoolKey pool, SegmentRange range) {
            throw new UnsupportedOperationException("not part of cancellation");
        }
    }

    private static final class FakeSchedules implements ScheduleQuery {
        PoolKey pool;
        BigDecimal distanceKm = new BigDecimal("730.00");

        @Override
        public Optional<PoolDescriptor> findPool(PoolKey key) {
            return Optional.of(
                    new PoolDescriptor(
                            pool, 72, 4, LocalDate.of(2026, 10, 6), DEPARTURE, false));
        }

        @Override
        public BigDecimal distanceKm(long scheduleId, SegmentRange range) {
            return distanceKm;
        }

        @Override
        public Optional<SegmentRange> resolveRange(long s, String from, String to) {
            throw new UnsupportedOperationException("resolved at the API boundary");
        }

        @Override
        public List<BerthDetail> describeBerths(List<Long> berthIds) {
            throw new UnsupportedOperationException("resolved at the API boundary");
        }
    }

    private static final class FakeGateway implements PaymentGateway {
        private final List<String> calls;
        private RefundOutcome next = new RefundOutcome.Accepted();
        int refunds;

        FakeGateway(List<String> calls) {
            this.calls = calls;
        }

        void unreachable() {
            next = new RefundOutcome.Unreachable("read timeout");
        }

        @Override
        public RefundOutcome refund(RefundRequest request) {
            calls.add("gateway.refund");
            refunds++;
            return next;
        }

        @Override
        public ChargeOutcome charge(ChargeRequest request) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public RemoteStatus poll(String paymentReference) {
            throw new UnsupportedOperationException("not part of cancellation");
        }
    }

    private static final class FakeBookings implements BookingRepository {
        private final List<String> calls;
        private final Map<Long, BookingView> rows = new HashMap<>();
        private boolean loseTransitions;

        FakeBookings(List<String> calls) {
            this.calls = calls;
        }

        void put(BookingView view) {
            rows.put(view.id(), view);
        }

        BookingView get(long id) {
            return rows.get(id);
        }

        void loseTransitions() {
            this.loseTransitions = true;
        }

        private boolean transition(long id, BookingStatus from, BookingStatus to) {
            if (loseTransitions) {
                return false;
            }
            var row = rows.get(id);
            if (row == null || row.status() != from) {
                return false;
            }
            rows.put(
                    id,
                    new BookingView(
                            row.id(), row.pnr(), to, row.pool(), row.range(),
                            row.passengerCount(), row.farePaise(), row.userId(),
                            row.holdExpiresAt(), row.berthIds()));
            return true;
        }

        @Override
        public Optional<BookingView> findByPnr(String pnr) {
            return rows.values().stream()
                    .filter(b -> b.pnr().map(pnr::equals).orElse(false))
                    .findFirst();
        }

        @Override
        public Optional<String> holdIdOf(long bookingId) {
            return Optional.of("hold-key-" + bookingId);
        }

        @Override
        public boolean cancel(long bookingId, Instant at) {
            calls.add("bookings.cancel");
            return transition(bookingId, BookingStatus.CONFIRMED, BookingStatus.CANCELLED);
        }

        @Override
        public boolean releaseHold(long bookingId, Instant at) {
            calls.add("bookings.releaseHold");
            return transition(bookingId, BookingStatus.HELD, BookingStatus.EXPIRED);
        }

        @Override
        public int deleteAllocations(long bookingId) {
            calls.add("bookings.deleteAllocations");
            return 2;
        }

        @Override
        public Optional<BookingView> findById(long bookingId) {
            return Optional.ofNullable(rows.get(bookingId));
        }

        @Override
        public Optional<BookingView> findByIdForUpdate(long bookingId) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public long createHeld(NewHeldBooking booking) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public int countActiveHolds(long userId, Instant now) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public AllocationOutcome persistAllocations(
                long bookingId, long scheduleId, SegmentRange range, List<Long> berthIds) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public boolean beginPayment(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public boolean markFailed(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public boolean markFailedRefunded(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public boolean confirm(long bookingId, String pnr, Instant confirmedAt) {
            throw new UnsupportedOperationException("not part of cancellation");
        }
    }

    private static final class FakePayments implements PaymentRepository {
        private final List<String> calls;
        private final Map<Long, PaymentRecord> captured = new HashMap<>();
        final List<Long> refundsOpened = new ArrayList<>();
        final List<String> refundsClosed = new ArrayList<>();
        private long nextRefundId = 500;

        FakePayments(List<String> calls) {
            this.calls = calls;
        }

        void capture(long bookingId, String reference, long amountPaise) {
            captured.put(
                    bookingId,
                    new PaymentRecord(
                            bookingId + 100, bookingId, reference, amountPaise,
                            PaymentStatus.SUCCESS, Instant.EPOCH, Optional.of(Instant.EPOCH)));
        }

        @Override
        public Optional<PaymentRecord> findCapturedFor(long bookingId) {
            return Optional.ofNullable(captured.get(bookingId));
        }

        @Override
        public long openRefund(
                long bookingId, long paymentId, long amountPaise, RefundReason reason) {
            calls.add("payments.openRefund");
            refundsOpened.add(amountPaise);
            return nextRefundId++;
        }

        @Override
        public void completeRefund(
                long refundId, long paymentId, long bookingId, long amountPaise) {
            refundsClosed.add("COMPLETED");
        }

        @Override
        public void failRefund(long refundId) {
            refundsClosed.add("FAILED");
        }

        @Override
        public void recordLedgerEntry(long bookingId, LedgerEntryType type, long amountPaise) {}

        @Override
        public Optional<PaymentRecord> findFor(long bookingId) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public Optional<PaymentRecord> findByReference(String reference) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public long create(NewPayment payment) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public boolean settle(String reference, PaymentStatus terminal, Instant settledAt) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public boolean recordEvent(String reference, String eventType, String payload) {
            throw new UnsupportedOperationException("not part of cancellation");
        }

        @Override
        public List<PendingSettlement> findPendingSettlements(Instant cutoff, int limit) {
            throw new UnsupportedOperationException("not part of cancellation");
        }
    }
}
