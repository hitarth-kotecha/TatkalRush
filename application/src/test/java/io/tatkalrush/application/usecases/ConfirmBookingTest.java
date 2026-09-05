package io.tatkalrush.application.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.IntegrityAlarm;
import io.tatkalrush.application.ports.PaymentGateway;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.PnrSequence;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.application.usecases.ConfirmBooking.Outcome;
import io.tatkalrush.application.usecases.ConfirmBooking.RefundSettlement;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.booking.Pnr;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import io.tatkalrush.domain.pricing.RefundReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-24 and FR-25: what happens when a payment settles.
 *
 * <p>The tests that matter most are the two that would still pass with steps 1
 * and 2 reordered if they were written carelessly, and fail loudly as written:
 * {@code expiredHoldNeverAttemptsTheAllocationInsert} and
 * {@code anExpiredHoldThatWouldAlsoConflictIsReportedAsExpiry}. Everything else
 * is scaffolding around those.
 */
class ConfirmBookingTest {

    private static final PoolKey POOL = new PoolKey(42L, TravelClass.SL, QuotaType.GENERAL);
    private static final SegmentRange RANGE = new SegmentRange(0, 4);
    private static final List<Long> BERTHS = List.of(7L, 8L);
    private static final long FARE = 123_400L;
    private static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");
    private static final Instant LIVE = NOW.plusSeconds(60);
    private static final Instant LAPSED = NOW.minusSeconds(1);

    private List<String> calls;
    private FakeBookings bookings;
    private FakePayments payments;
    private FakeGateway gateway;
    private CountingSequence sequence;
    private RecordingAlarm alarm;
    private TrackingUnitOfWork unitOfWork;
    private ConfirmBooking confirmBooking;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        unitOfWork = new TrackingUnitOfWork(calls);
        bookings = new FakeBookings(calls);
        payments = new FakePayments(calls);
        gateway = new FakeGateway(calls, unitOfWork);
        sequence = new CountingSequence();
        alarm = new RecordingAlarm();
        confirmBooking =
                new ConfirmBooking(bookings, payments, gateway, sequence, alarm, unitOfWork);
    }

    /** A booking mid-payment, with a captured payment behind it. */
    private long givenPaymentPending(Instant holdExpiresAt) {
        long id =
                bookings.put(
                        new BookingRepository.BookingView(
                                1L,
                                Optional.empty(),
                                BookingStatus.PAYMENT_PENDING,
                                POOL,
                                RANGE,
                                2,
                                FARE,
                                900L,
                                Optional.ofNullable(holdExpiresAt),
                                BERTHS));
        payments.capture(id, "psp-ref-1", FARE);
        return id;
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-25 step 1: the hold must be live")
    class Step1 {

        @Test
        void anExpiredHoldRefundsWithHoldExpired() {
            long id = givenPaymentPending(LAPSED);

            var outcome = confirmBooking.confirm(id, NOW);

            var refunded = assertInstanceOf(Outcome.Refunded.class, outcome);
            assertEquals(RefundReason.HOLD_EXPIRED, refunded.reason());
            assertFalse(refunded.indicatesDefect(), "an expiry race is not a defect (FR-24)");
            assertEquals(
                    BookingStatus.FAILED_REFUNDED,
                    bookings.get(id).status(),
                    "FR-24 moves the booking to FAILED_REFUNDED");
        }

        /**
         * The ordering test. If steps 1 and 2 were swapped this would fail here,
         * long before anyone noticed that INV-11's signal had become ambiguous.
         */
        @Test
        void expiredHoldNeverAttemptsTheAllocationInsert() {
            long id = givenPaymentPending(LAPSED);

            confirmBooking.confirm(id, NOW);

            assertFalse(
                    calls.contains("bookings.persistAllocations"),
                    "FR-25 step 1 precedes step 2; an expired hold must not reach the "
                            + "exclusion constraint at all. Calls: "
                            + calls);
        }

        @Test
        void aHoldExpiringExactlyNowIsExpired() {
            // FR-25 says hold_expires_at > now. At equality the hold is dead, and
            // the strictness matters: a reaper releasing berths at exactly this
            // instant may already have resold them.
            long id = givenPaymentPending(NOW);

            var outcome = confirmBooking.confirm(id, NOW);

            assertEquals(
                    RefundReason.HOLD_EXPIRED,
                    assertInstanceOf(Outcome.Refunded.class, outcome).reason());
        }

        @Test
        void aPaymentPendingBookingWithNoExpiryFailsLoudly() {
            long id = givenPaymentPending(null);

            var thrown =
                    assertThrows(IllegalStateException.class, () -> confirmBooking.confirm(id, NOW));

            assertTrue(
                    thrown.getMessage().contains("hold_expires_at"),
                    "the message must name the missing column; guessing an answer to "
                            + "step 1 either way is worse than failing: "
                            + thrown.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-25 step 2: the exclusion constraint")
    class Step2 {

        @Test
        void aConflictAgainstALiveHoldIsAnAllocatorDefect() {
            long id = givenPaymentPending(LIVE);
            bookings.conflictOn(8L);

            var outcome = confirmBooking.confirm(id, NOW);

            var refunded = assertInstanceOf(Outcome.Refunded.class, outcome);
            assertEquals(RefundReason.ALLOCATION_CONFLICT, refunded.reason());
            assertTrue(refunded.indicatesDefect(), "INV-11 fails the run on this");
        }

        @Test
        void aConflictAgainstALiveHoldRaisesTheIntegrityAlarm() {
            long id = givenPaymentPending(LIVE);
            bookings.conflictOn(8L);

            confirmBooking.confirm(id, NOW);

            assertEquals(
                    List.of("booking=" + id + " berth=8"),
                    alarm.violations,
                    "NFR-9's allocation_constraint_violations_total must name the berth");
        }

        /**
         * The discriminating case, and the reason FR-25 fixes the order.
         *
         * <p>Both conditions hold at once: the hold has lapsed <em>and</em> the
         * constraint would reject the insert. Under FR-25's order this is the
         * benign FR-24 race. Under the reverse order it would be reported as an
         * allocator defect — failing the run, at scale, during exactly the chaos
         * scenario (C2) designed to produce it.
         */
        @Test
        void anExpiredHoldThatWouldAlsoConflictIsReportedAsExpiry() {
            long id = givenPaymentPending(LAPSED);
            bookings.conflictOn(8L);

            var outcome = confirmBooking.confirm(id, NOW);

            assertEquals(
                    RefundReason.HOLD_EXPIRED,
                    assertInstanceOf(Outcome.Refunded.class, outcome).reason());
            assertTrue(
                    alarm.violations.isEmpty(),
                    "a benign expiry race must not accuse the allocator; alarm saw: "
                            + alarm.violations);
        }

        @Test
        void aConflictStillMovesTheBookingToFailedRefunded() {
            long id = givenPaymentPending(LIVE);
            bookings.conflictOn(7L);

            confirmBooking.confirm(id, NOW);

            assertEquals(BookingStatus.FAILED_REFUNDED, bookings.get(id).status());
            assertFalse(
                    bookings.get(id).pnr().isPresent(),
                    "a booking that was never confirmed must carry no PNR (§6.4)");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-25 step 3: confirmation and the PNR")
    class Step3 {

        @Test
        void aLiveHoldConfirmsAndIssuesAPnr() {
            long id = givenPaymentPending(LIVE);

            var outcome = confirmBooking.confirm(id, NOW);

            var confirmed = assertInstanceOf(Outcome.Confirmed.class, outcome);
            assertEquals(BERTHS, confirmed.berthIds());
            assertEquals(BookingStatus.CONFIRMED, bookings.get(id).status());
            assertEquals(Optional.of(confirmed.pnr()), bookings.get(id).pnr());
        }

        @Test
        void thePnrPassesItsOwnCheckDigit() {
            long id = givenPaymentPending(LIVE);

            var confirmed =
                    assertInstanceOf(Outcome.Confirmed.class, confirmBooking.confirm(id, NOW));

            // The Pnr constructor validates length and Luhn. INV-6 recomputes the
            // same thing across the whole table after a run.
            assertEquals(10, confirmed.pnr().length());
            new Pnr(confirmed.pnr());
        }

        @Test
        void thePnrComesFromTheSequenceNotFromRandomness() {
            long first = givenPaymentPending(LIVE);
            var a = assertInstanceOf(Outcome.Confirmed.class, confirmBooking.confirm(first, NOW));

            long second =
                    bookings.put(
                            new BookingRepository.BookingView(
                                    2L,
                                    Optional.empty(),
                                    BookingStatus.PAYMENT_PENDING,
                                    POOL,
                                    new SegmentRange(4, 6),
                                    1,
                                    FARE,
                                    901L,
                                    Optional.of(LIVE),
                                    List.of(9L)));
            payments.capture(second, "psp-ref-2", FARE);
            var b = assertInstanceOf(Outcome.Confirmed.class, confirmBooking.confirm(second, NOW));

            assertEquals(2, sequence.issued, "one sequence value per confirmation");
            assertEquals(Pnr.fromSequence(1).value(), a.pnr());
            assertEquals(Pnr.fromSequence(2).value(), b.pnr());
        }

        /** FR-25's steps are observable in order, not merely intended. */
        @Test
        void allocationsArePersistedBeforeTheTransition() {
            long id = givenPaymentPending(LIVE);

            confirmBooking.confirm(id, NOW);

            assertTrue(
                    calls.indexOf("bookings.persistAllocations") < calls.indexOf("bookings.confirm"),
                    "step 2 precedes step 3: " + calls);
        }

        @Test
        void confirmationWritesNoLedgerEntry() {
            long id = givenPaymentPending(LIVE);

            confirmBooking.confirm(id, NOW);

            // The CHARGE entry belongs to settlement, where the money actually
            // moved. Writing it here would leave a HOLD_EXPIRED refund with a
            // REFUND entry and no matching CHARGE, and the ledger would not
            // balance for INV-2.
            assertTrue(payments.ledger.isEmpty(), "ledger: " + payments.ledger);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Redelivery and races")
    class Idempotency {

        @Test
        void theBookingRowIsLockedNotMerelyRead() {
            long id = givenPaymentPending(LIVE);

            confirmBooking.confirm(id, NOW);

            assertTrue(calls.contains("bookings.findByIdForUpdate"), calls.toString());
            assertFalse(
                    calls.contains("bookings.findById"),
                    "an unlocked read lets FR-22's webhook and FR-23's poll both insert, "
                            + "and the second insert looks like an allocator defect");
        }

        @Test
        void anAlreadyConfirmedBookingIsANoOp() {
            long id = givenPaymentPending(LIVE);
            confirmBooking.confirm(id, NOW);
            calls.clear();

            var outcome = confirmBooking.confirm(id, NOW);

            var settled = assertInstanceOf(Outcome.AlreadySettled.class, outcome);
            assertEquals(BookingStatus.CONFIRMED, settled.status());
            assertEquals(1, sequence.issued, "a redelivered webhook must not burn a second PNR");
            assertFalse(calls.contains("bookings.persistAllocations"), calls.toString());
        }

        @Test
        void losingTheConfirmCompareAndSetReportsAlreadySettled() {
            long id = givenPaymentPending(LIVE);
            bookings.loseTheConfirmCas();

            var outcome = confirmBooking.confirm(id, NOW);

            assertInstanceOf(Outcome.AlreadySettled.class, outcome);
        }

        /**
         * A duplicate confirmation that slipped past the row lock is a bug in this
         * layer, and it must not be reported as the allocator's. The repository
         * distinguishes it by querying before inserting; the database cannot,
         * because both cases raise the same 23P01.
         */
        @Test
        void aDuplicateOfThisBookingsOwnRowsIsNotAnAllocatorDefect() {
            long id = givenPaymentPending(LIVE);
            bookings.allocationsAlreadyPresent();
            bookings.loseTheConfirmCas();

            var outcome = confirmBooking.confirm(id, NOW);

            assertInstanceOf(Outcome.AlreadySettled.class, outcome);
            assertTrue(alarm.violations.isEmpty(), "alarm saw: " + alarm.violations);
        }

        @Test
        void anUnknownBookingIsReportedNotThrown() {
            var outcome = confirmBooking.confirm(999L, NOW);

            assertEquals(new Outcome.UnknownBooking(999L), outcome);
        }

        @Test
        void anExpiredBookingIsLeftAlone() {
            long id =
                    bookings.put(
                            new BookingRepository.BookingView(
                                    3L,
                                    Optional.empty(),
                                    BookingStatus.EXPIRED,
                                    POOL,
                                    RANGE,
                                    1,
                                    FARE,
                                    902L,
                                    Optional.of(LAPSED),
                                    BERTHS));

            var outcome = confirmBooking.confirm(id, NOW);

            assertEquals(new Outcome.AlreadySettled(id, BookingStatus.EXPIRED), outcome);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("The refund is a compensating transaction")
    class Refunding {

        @Test
        void theIntentIsRecordedBeforeTheGatewayIsCalled() {
            long id = givenPaymentPending(LAPSED);

            confirmBooking.confirm(id, NOW);

            assertTrue(
                    calls.indexOf("payments.openRefund") < calls.indexOf("gateway.refund"),
                    "a PENDING row written first is what a retry has to work from; "
                            + "calling first and recording after loses money silently: "
                            + calls);
        }

        @Test
        void theGatewayIsNotCalledInsideATransaction() {
            long id = givenPaymentPending(LAPSED);

            confirmBooking.confirm(id, NOW);

            assertEquals(
                    0,
                    gateway.transactionDepthAtCall,
                    "FR-53 puts the PSP's p99 at 6 s; holding a pooled connection across "
                            + "that call is how 20 connections come to serve 3 rps");
        }

        @Test
        void anAcceptedRefundCompletesAndWritesTheLedgerEntry() {
            long id = givenPaymentPending(LAPSED);

            var outcome = confirmBooking.confirm(id, NOW);

            assertEquals(
                    RefundSettlement.COMPLETED,
                    assertInstanceOf(Outcome.Refunded.class, outcome).settlement());
            assertEquals(List.of("COMPLETED"), payments.refundStates);
            assertEquals(
                    List.of("REFUND:" + FARE), payments.ledger, "the refund movement is recorded");
        }

        @Test
        void aRejectedRefundIsMarkedFailed() {
            long id = givenPaymentPending(LAPSED);
            gateway.rejectRefunds("insufficient merchant balance");

            var outcome = confirmBooking.confirm(id, NOW);

            assertEquals(
                    RefundSettlement.FAILED,
                    assertInstanceOf(Outcome.Refunded.class, outcome).settlement());
            assertEquals(List.of("FAILED"), payments.refundStates);
            assertTrue(payments.ledger.isEmpty(), "no money moved, so nothing is recorded");
        }

        /**
         * The case that separates "no" from "no answer". Marking this FAILED would
         * assert the money is still with the PSP when it may not be, and a retry
         * would then refund twice.
         */
        @Test
        void anUnreachableGatewayLeavesTheRefundPending() {
            long id = givenPaymentPending(LAPSED);
            gateway.refundsUnreachable("read timeout");

            var outcome = confirmBooking.confirm(id, NOW);

            assertEquals(
                    RefundSettlement.PENDING,
                    assertInstanceOf(Outcome.Refunded.class, outcome).settlement());
            assertTrue(payments.refundStates.isEmpty(), "the row is left PENDING for a retry");
            assertEquals(
                    BookingStatus.FAILED_REFUNDED,
                    bookings.get(id).status(),
                    "the berth is not this customer's regardless of where the money is");
        }

        @Test
        void refundingWithNoCapturedPaymentIsRefused() {
            long id = givenPaymentPending(LAPSED);
            payments.forgetCapture(id);

            var thrown =
                    assertThrows(IllegalStateException.class, () -> confirmBooking.confirm(id, NOW));

            assertTrue(
                    thrown.getMessage().contains("no captured payment"),
                    "refunding against an intent sends money that was never taken: "
                            + thrown.getMessage());
        }
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    /** Runs work inline and records how deep the transaction nesting is. */
    private static final class TrackingUnitOfWork implements UnitOfWork {
        private final List<String> calls;
        private int depth;

        TrackingUnitOfWork(List<String> calls) {
            this.calls = calls;
        }

        int depth() {
            return depth;
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            depth++;
            calls.add("tx.begin");
            try {
                T result = work.get();
                calls.add("tx.commit");
                return result;
            } finally {
                depth--;
            }
        }
    }

    private static final class FakeBookings implements BookingRepository {
        private final List<String> calls;
        private final Map<Long, BookingView> rows = new HashMap<>();
        private long nextId = 1;
        private Long conflictingBerth;
        private boolean alreadyPresent;
        private boolean loseCas;

        FakeBookings(List<String> calls) {
            this.calls = calls;
        }

        long put(BookingView view) {
            long id = nextId++;
            rows.put(id, withId(view, id));
            return id;
        }

        BookingView get(long id) {
            return rows.get(id);
        }

        void conflictOn(long berthId) {
            this.conflictingBerth = berthId;
        }

        void allocationsAlreadyPresent() {
            this.alreadyPresent = true;
        }

        void loseTheConfirmCas() {
            this.loseCas = true;
        }

        @Override
        public Optional<BookingView> findByIdForUpdate(long bookingId) {
            calls.add("bookings.findByIdForUpdate");
            return Optional.ofNullable(rows.get(bookingId));
        }

        @Override
        public Optional<BookingView> findById(long bookingId) {
            calls.add("bookings.findById");
            return Optional.ofNullable(rows.get(bookingId));
        }

        @Override
        public AllocationOutcome persistAllocations(
                long bookingId, long scheduleId, SegmentRange range, List<Long> berthIds) {
            calls.add("bookings.persistAllocations");
            if (conflictingBerth != null && berthIds.contains(conflictingBerth)) {
                return new AllocationOutcome.Conflict(conflictingBerth);
            }
            if (alreadyPresent) {
                return new AllocationOutcome.AlreadyPresent();
            }
            return new AllocationOutcome.Persisted();
        }

        @Override
        public boolean confirm(long bookingId, String pnr, Instant confirmedAt) {
            calls.add("bookings.confirm");
            if (loseCas) {
                return false;
            }
            var row = rows.get(bookingId);
            if (row.status() != BookingStatus.PAYMENT_PENDING) {
                return false;
            }
            rows.put(
                    bookingId,
                    new BookingView(
                            row.id(),
                            Optional.of(pnr),
                            BookingStatus.CONFIRMED,
                            row.pool(),
                            row.range(),
                            row.passengerCount(),
                            row.farePaise(),
                            row.userId(),
                            row.holdExpiresAt(),
                            row.berthIds()));
            return true;
        }

        @Override
        public boolean markFailedRefunded(long bookingId, Instant at) {
            calls.add("bookings.markFailedRefunded");
            var row = rows.get(bookingId);
            if (row.status() != BookingStatus.PAYMENT_PENDING) {
                return false;
            }
            rows.put(
                    bookingId,
                    new BookingView(
                            row.id(),
                            row.pnr(),
                            BookingStatus.FAILED_REFUNDED,
                            row.pool(),
                            row.range(),
                            row.passengerCount(),
                            row.farePaise(),
                            row.userId(),
                            row.holdExpiresAt(),
                            row.berthIds()));
            return true;
        }

        @Override
        public long createHeld(NewHeldBooking booking) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public int countActiveHolds(long userId, Instant now) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public boolean beginPayment(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public boolean markFailed(long bookingId, Instant at) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        private static BookingView withId(BookingView v, long id) {
            return new BookingView(
                    id,
                    v.pnr(),
                    v.status(),
                    v.pool(),
                    v.range(),
                    v.passengerCount(),
                    v.farePaise(),
                    v.userId(),
                    v.holdExpiresAt(),
                    v.berthIds());
        }
    }

    private static final class FakePayments implements PaymentRepository {
        private final List<String> calls;
        private final Map<Long, PaymentRecord> captured = new HashMap<>();
        final List<String> refundStates = new ArrayList<>();
        final List<String> ledger = new ArrayList<>();
        private long nextRefundId = 500;

        FakePayments(List<String> calls) {
            this.calls = calls;
        }

        void capture(long bookingId, String reference, long amountPaise) {
            captured.put(
                    bookingId,
                    new PaymentRecord(
                            bookingId + 100,
                            bookingId,
                            reference,
                            amountPaise,
                            PaymentStatus.SUCCESS,
                            Instant.EPOCH,
                            Optional.of(Instant.EPOCH)));
        }

        void forgetCapture(long bookingId) {
            captured.remove(bookingId);
        }

        @Override
        public Optional<PaymentRecord> findCapturedFor(long bookingId) {
            calls.add("payments.findCapturedFor");
            return Optional.ofNullable(captured.get(bookingId));
        }

        @Override
        public long openRefund(
                long bookingId, long paymentId, long amountPaise, RefundReason reason) {
            calls.add("payments.openRefund");
            return nextRefundId++;
        }

        @Override
        public void completeRefund(
                long refundId, long paymentId, long bookingId, long amountPaise) {
            calls.add("payments.completeRefund");
            refundStates.add("COMPLETED");
            recordLedgerEntry(bookingId, LedgerEntryType.REFUND, amountPaise);
        }

        @Override
        public void failRefund(long refundId) {
            calls.add("payments.failRefund");
            refundStates.add("FAILED");
        }

        @Override
        public void recordLedgerEntry(long bookingId, LedgerEntryType type, long amountPaise) {
            ledger.add(type + ":" + amountPaise);
        }

        // Settlement (FR-21 to FR-23). Confirmation is called with the payment
        // already captured, so none of this is reachable from here.

        @Override
        public Optional<PaymentRecord> findFor(long bookingId) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public Optional<PaymentRecord> findByReference(String paymentReference) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public long create(NewPayment payment) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public boolean settle(String reference, PaymentStatus terminal, Instant settledAt) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public boolean recordEvent(String reference, String eventType, String payload) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public List<PendingSettlement> findPendingSettlements(Instant cutoff, int limit) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }
    }

    private static final class FakeGateway implements PaymentGateway {
        private final List<String> calls;
        private final TrackingUnitOfWork unitOfWork;
        private RefundOutcome nextRefund = new RefundOutcome.Accepted();
        int transactionDepthAtCall = -1;

        FakeGateway(List<String> calls, TrackingUnitOfWork unitOfWork) {
            this.calls = calls;
            this.unitOfWork = unitOfWork;
        }

        void rejectRefunds(String reason) {
            nextRefund = new RefundOutcome.Rejected(reason);
        }

        void refundsUnreachable(String detail) {
            nextRefund = new RefundOutcome.Unreachable(detail);
        }

        @Override
        public RefundOutcome refund(RefundRequest request) {
            calls.add("gateway.refund");
            transactionDepthAtCall = unitOfWork.depth();
            return nextRefund;
        }

        @Override
        public ChargeOutcome charge(ChargeRequest request) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }

        @Override
        public RemoteStatus poll(String paymentReference) {
            throw new UnsupportedOperationException("not part of the confirmation path");
        }
    }

    private static final class CountingSequence implements PnrSequence {
        int issued;

        @Override
        public long next() {
            return ++issued;
        }
    }

    private static final class RecordingAlarm implements IntegrityAlarm {
        final List<String> violations = new ArrayList<>();

        @Override
        public void allocationConstraintViolated(long bookingId, long berthId) {
            violations.add("booking=" + bookingId + " berth=" + berthId);
        }
    }
}
