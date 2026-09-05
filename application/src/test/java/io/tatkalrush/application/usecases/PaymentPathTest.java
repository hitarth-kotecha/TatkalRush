package io.tatkalrush.application.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.IntegrityAlarm;
import io.tatkalrush.application.ports.PaymentGateway;
import io.tatkalrush.application.ports.PaymentReferences;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.PaymentRepository.PaymentStatus;
import io.tatkalrush.application.ports.PnrSequence;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import io.tatkalrush.domain.pricing.RefundReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-21, FR-22 and FR-23: initiating a payment, and settling one you may be told
 * about twice, out of order, or never.
 *
 * <p>The tests carrying the most weight are the ones about what happens when
 * nobody tells you anything: {@code aNeverDeliveredWebhookIsRecoveredByThePoll}
 * and {@code aCrashBetweenSettlementAndConfirmationIsRepaired}. The second is the
 * reason reconciliation is driven by state rather than by events.
 */
class PaymentPathTest {

    private static final PoolKey POOL = new PoolKey(42L, TravelClass.SL, QuotaType.GENERAL);
    private static final SegmentRange RANGE = new SegmentRange(0, 4);
    private static final List<Long> BERTHS = List.of(7L);
    private static final long FARE = 123_400L;
    private static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");
    private static final Instant LIVE = NOW.plusSeconds(60);
    private static final Instant LAPSED = NOW.minusSeconds(1);

    /**
     * Long enough to survive a reconciliation sweep. FR-23 does not run until a
     * payment is 60 s old, so a hold with FR-17's 120 s TTL would have lapsed by
     * the time the sweep sees it - which is a real scenario, but not the one
     * these tests are about.
     */
    private static final Instant LIVE_PAST_THE_SWEEP = NOW.plusSeconds(600);

    private List<String> calls;
    private FakeBookings bookings;
    private FakePayments payments;
    private FakeGateway gateway;
    private RecordingAlarm alarm;
    private TrackingUnitOfWork unitOfWork;
    private InitiatePayment initiatePayment;
    private SettlePayment settlePayment;

    @BeforeEach
    void setUp() {
        calls = new ArrayList<>();
        unitOfWork = new TrackingUnitOfWork(calls);
        bookings = new FakeBookings(calls);
        payments = new FakePayments(calls, bookings);
        gateway = new FakeGateway(calls, unitOfWork);
        alarm = new RecordingAlarm();

        var confirmBooking =
                new ConfirmBooking(
                        bookings,
                        payments,
                        gateway,
                        new CountingSequence(),
                        alarm,
                        unitOfWork);

        initiatePayment =
                new InitiatePayment(
                        bookings, payments, gateway, new FixedReferences(), unitOfWork);
        settlePayment =
                new SettlePayment(bookings, payments, gateway, confirmBooking, unitOfWork);
    }

    private long givenHeld(Instant holdExpiresAt) {
        return bookings.put(BookingStatus.HELD, holdExpiresAt);
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-21: initiating a payment")
    class Initiating {

        @Test
        void aLiveHoldMovesToPaymentPendingAndCallsTheGateway() {
            long id = givenHeld(LIVE);

            var result = initiatePayment.initiate(id, NOW);

            var initiated = assertInstanceOf(InitiatePayment.Result.Initiated.class, result);
            assertEquals(FARE, initiated.amountPaise());
            assertEquals(BookingStatus.PAYMENT_PENDING, bookings.get(id).status());
            assertEquals(
                    PaymentStatus.INITIATED, payments.byReference(initiated.reference()).status());
        }

        /**
         * The ordering that makes a crash recoverable. Reversed, a crash between
         * the calls leaves money captured with nothing pointing at it.
         */
        @Test
        void theIntentIsCommittedBeforeTheGatewayIsCalled() {
            long id = givenHeld(LIVE);

            initiatePayment.initiate(id, NOW);

            assertTrue(
                    calls.indexOf("payments.create") < calls.indexOf("gateway.charge"),
                    "intent first, charge second: " + calls);
            assertTrue(
                    calls.indexOf("tx.commit") < calls.indexOf("gateway.charge"),
                    "the intent must be COMMITTED, not merely written: " + calls);
        }

        @Test
        void theGatewayIsNotCalledInsideATransaction() {
            long id = givenHeld(LIVE);

            initiatePayment.initiate(id, NOW);

            assertEquals(0, gateway.chargeTransactionDepth, "FR-53 puts the PSP's p99 at 6 s");
        }

        @Test
        void aSecondInitiationReturnsTheExistingPaymentRatherThanChargingAgain() {
            long id = givenHeld(LIVE);
            var first = assertInstanceOf(
                    InitiatePayment.Result.Initiated.class, initiatePayment.initiate(id, NOW));

            var second = initiatePayment.initiate(id, NOW);

            var already =
                    assertInstanceOf(InitiatePayment.Result.AlreadyInitiated.class, second);
            assertEquals(first.reference(), already.reference());
            assertEquals(1, gateway.charges, "API-5's idempotency is the state machine (FR-27)");
            assertEquals(1, payments.count(), "a second payment row would be a second charge");
        }

        @Test
        void anExpiredHoldIsRefusedWithoutCharging() {
            long id = givenHeld(LAPSED);

            var result = initiatePayment.initiate(id, NOW);

            assertInstanceOf(InitiatePayment.Result.HoldExpired.class, result);
            assertEquals(0, gateway.charges, "capturing money for a dead hold manufactures FR-24");
            assertEquals(BookingStatus.HELD, bookings.get(id).status());
        }

        @Test
        void aDeclinedChargeFailsTheBookingWithNothingToRefund() {
            long id = givenHeld(LIVE);
            gateway.declineCharges("card declined");

            var result = initiatePayment.initiate(id, NOW);

            assertInstanceOf(InitiatePayment.Result.Declined.class, result);
            assertEquals(
                    BookingStatus.FAILED,
                    bookings.get(id).status(),
                    "FAILED, not FAILED_REFUNDED - nothing was captured");
            assertTrue(payments.ledger.isEmpty(), "no money moved: " + payments.ledger);
        }

        /**
         * The mistake this rules out is the most common one in payment code:
         * treating "no answer" as "no".
         */
        @Test
        void anUnreachableGatewayLeavesThePaymentInitiated() {
            long id = givenHeld(LIVE);
            gateway.chargesUnreachable("read timeout");

            var result = initiatePayment.initiate(id, NOW);

            assertInstanceOf(InitiatePayment.Result.OutcomeUnknown.class, result);
            assertEquals(
                    PaymentStatus.INITIATED,
                    payments.only().status(),
                    "the charge may have landed; FR-23 finds out");
            assertEquals(
                    BookingStatus.PAYMENT_PENDING,
                    bookings.get(id).status(),
                    "failing the booking here would strand a possibly-captured payment");
        }

        @Test
        void aBookingThatIsNotHeldCannotBePaidFor() {
            long id = bookings.put(BookingStatus.EXPIRED, LAPSED);

            var result = initiatePayment.initiate(id, NOW);

            assertEquals(
                    BookingStatus.EXPIRED,
                    assertInstanceOf(InitiatePayment.Result.NotPayable.class, result).status());
            assertEquals(0, gateway.charges);
        }

        @Test
        void anUnknownBookingIsReportedNotThrown() {
            assertInstanceOf(
                    InitiatePayment.Result.UnknownBooking.class,
                    initiatePayment.initiate(999L, NOW));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-22: the webhook")
    class Webhooks {

        @Test
        void aSuccessWebhookSettlesAndConfirms() {
            String ref = initiated(LIVE);

            var result = settlePayment.handle(success(ref), NOW);

            var settled = assertInstanceOf(SettlePayment.Result.Settled.class, result);
            assertInstanceOf(ConfirmBooking.Outcome.Confirmed.class, settled.confirmation());
            assertEquals(PaymentStatus.SUCCESS, payments.byReference(ref).status());
            assertEquals(BookingStatus.CONFIRMED, bookings.get(settled.bookingId()).status());
        }

        /** T-C5. FR-55 double-delivers 5% of webhooks on purpose. */
        @Test
        void aRedeliveredWebhookIsANoOp() {
            String ref = initiated(LIVE);
            settlePayment.handle(success(ref), NOW);
            int ledgerAfterFirst = payments.ledger.size();

            var result = settlePayment.handle(success(ref), NOW.plusSeconds(1));

            assertInstanceOf(SettlePayment.Result.DuplicateEvent.class, result);
            assertEquals(
                    ledgerAfterFirst,
                    payments.ledger.size(),
                    "a second CHARGE entry is a double charge in the ledger");
        }

        @Test
        void theEventIsRecordedInTheSameTransactionAsTheSettlement() {
            String ref = initiated(LIVE);

            settlePayment.handle(success(ref), NOW);

            int event = calls.indexOf("payments.recordEvent");
            int settle = calls.indexOf("payments.settle");
            int commit = event + calls.subList(event, calls.size()).indexOf("tx.commit");
            assertTrue(event < settle && settle < commit,
                    "a dedup marker committed alone would suppress a redelivery for a "
                            + "settlement that never happened: " + calls);
        }

        /** FR-22 names out-of-order delivery explicitly. */
        @Test
        void aLateFailureCannotUnconfirmABooking() {
            String ref = initiated(LIVE);
            settlePayment.handle(success(ref), NOW);

            var result = settlePayment.handle(failure(ref), NOW.plusSeconds(2));

            var already = assertInstanceOf(SettlePayment.Result.AlreadySettled.class, result);
            assertEquals(PaymentStatus.SUCCESS, already.current());
            assertEquals(BookingStatus.CONFIRMED, bookings.get(1L).status());
        }

        @Test
        void aLateFailureIsStillRecordedForAudit() {
            String ref = initiated(LIVE);
            settlePayment.handle(success(ref), NOW);

            settlePayment.handle(failure(ref), NOW.plusSeconds(2));

            assertTrue(
                    payments.events.containsKey(ref + "/PAYMENT_FAILED"),
                    "the trail should show what the PSP said, even said too late: "
                            + payments.events.keySet());
        }

        @Test
        void aFailureWebhookFailsTheBookingWithoutARefund() {
            String ref = initiated(LIVE);

            var result = settlePayment.handle(failure(ref), NOW);

            long bookingId = assertInstanceOf(SettlePayment.Result.Failed.class, result).bookingId();
            assertEquals(BookingStatus.FAILED, bookings.get(bookingId).status());
            assertEquals(0, gateway.refunds, "nothing was captured, so nothing is refunded");
        }

        /** T-C4: the money arrives after the hold lapsed. */
        @Test
        void aSuccessAgainstALapsedHoldRefundsAsHoldExpired() {
            String ref = initiated(LIVE);
            bookings.expireHold(1L, LAPSED);

            var result = settlePayment.handle(success(ref), NOW);

            var settled = assertInstanceOf(SettlePayment.Result.Settled.class, result);
            var refunded =
                    assertInstanceOf(
                            ConfirmBooking.Outcome.Refunded.class, settled.confirmation());
            assertEquals(RefundReason.HOLD_EXPIRED, refunded.reason());
            assertFalse(refunded.indicatesDefect());
            assertTrue(alarm.violations.isEmpty(), "a benign race must not accuse the allocator");
        }

        @Test
        void theChargeLedgerEntryIsWrittenWhereTheMoneyMoved() {
            String ref = initiated(LIVE);
            bookings.expireHold(1L, LAPSED);

            settlePayment.handle(success(ref), NOW);

            // Both entries, in this order. Writing CHARGE at confirmation instead
            // would leave this refund with no matching charge and INV-2 could not
            // balance the ledger.
            assertEquals(List.of("CHARGE:" + FARE, "REFUND:" + FARE), payments.ledger);
        }

        @Test
        void aWebhookForAnUnknownReferenceIsReportedNotThrown() {
            var result = settlePayment.handle(success("no-such-ref"), NOW);

            assertInstanceOf(SettlePayment.Result.UnknownPayment.class, result);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-23: reconciliation, for when nobody tells you")
    class Reconciliation {

        /** T-C6, and FR-54's webhook-never-sent outcome. */
        @Test
        void aNeverDeliveredWebhookIsRecoveredByThePoll() {
            String ref = initiated(LIVE_PAST_THE_SWEEP);
            gateway.remoteStatus(ref, PaymentGateway.RemoteStatus.SUCCESS);

            var report = settlePayment.reconcile(NOW.plusSeconds(120));

            assertEquals(1, report.settled());
            assertEquals(1, report.confirmed());
            assertEquals(BookingStatus.CONFIRMED, bookings.get(1L).status());
        }

        /**
         * The case an event-driven sweep cannot see, and the reason FR-23 selects
         * by state.
         */
        @Test
        void aCrashBetweenSettlementAndConfirmationIsRepaired() {
            String ref = initiated(LIVE_PAST_THE_SWEEP);
            // Exactly the state a crash leaves: payment SUCCESS, event recorded,
            // booking never advanced.
            payments.settle(ref, PaymentStatus.SUCCESS, NOW);
            payments.recordEvent(ref, "PAYMENT_SUCCEEDED", "{}");
            calls.clear();

            var report = settlePayment.reconcile(NOW.plusSeconds(120));

            assertEquals(1, report.confirmed());
            assertEquals(BookingStatus.CONFIRMED, bookings.get(1L).status());
            assertEquals(
                    0,
                    gateway.polls,
                    "the PSP has nothing to add here; this is a repair, not an enquiry");
        }

        @Test
        void aPaymentTheGatewayNeverHeardOfIsAbandoned() {
            String ref = initiated(LIVE_PAST_THE_SWEEP);
            gateway.remoteStatus(ref, PaymentGateway.RemoteStatus.UNKNOWN);

            var report = settlePayment.reconcile(NOW.plusSeconds(120));

            assertEquals(1, report.abandoned());
            assertEquals(
                    BookingStatus.FAILED,
                    bookings.get(1L).status(),
                    "we crashed before the charge landed; no money moved");
            assertTrue(payments.ledger.isEmpty());
        }

        @Test
        void aPaymentTheGatewayIsStillWorkingOnIsLeftAlone() {
            String ref = initiated(LIVE_PAST_THE_SWEEP);
            gateway.remoteStatus(ref, PaymentGateway.RemoteStatus.INITIATED);

            var report = settlePayment.reconcile(NOW.plusSeconds(120));

            assertEquals(1, report.stillPending());
            assertEquals(0, report.settled());
            assertEquals(BookingStatus.PAYMENT_PENDING, bookings.get(1L).status());
        }

        @Test
        void paymentsYoungerThanTheWindowAreNotTouched() {
            String ref = initiated(LIVE_PAST_THE_SWEEP);
            gateway.remoteStatus(ref, PaymentGateway.RemoteStatus.SUCCESS);

            // FR-53's median is 800 ms and its p99 six seconds; sweeping at 10 s
            // would poll for answers that are simply still in flight.
            var report = settlePayment.reconcile(NOW.plusSeconds(10), Duration.ofSeconds(60), 200);

            assertEquals(0, report.examined());
            assertEquals(0, gateway.polls);
        }

        @Test
        void aLapsedHoldFoundByTheSweepIsCountedAsRefundedNotConfirmed() {
            String ref = initiated(LIVE_PAST_THE_SWEEP);
            bookings.expireHold(1L, LAPSED);
            gateway.remoteStatus(ref, PaymentGateway.RemoteStatus.SUCCESS);

            var report = settlePayment.reconcile(NOW.plusSeconds(120));

            assertEquals(1, report.settled());
            assertEquals(0, report.confirmed(), "the customer did not get a seat");
            assertEquals(1, report.refunded(), "C5 measures exactly this number");
        }

        @Test
        void theSweepDoesNotReExamineBookingsThatHaveMovedOn() {
            String ref = initiated(LIVE_PAST_THE_SWEEP);
            gateway.remoteStatus(ref, PaymentGateway.RemoteStatus.SUCCESS);
            settlePayment.handle(success(ref), NOW);

            var report = settlePayment.reconcile(NOW.plusSeconds(120));

            assertEquals(0, report.examined(), "the booking is no longer PAYMENT_PENDING");
            assertEquals(0, gateway.polls, "and the PSP is not asked about it");
        }

        /**
         * The case FR-22's unique key cannot cover, and the reason the status
         * compare-and-set exists.
         *
         * <p>The sweep settles first and writes <b>no</b> {@code payment_events}
         * row — it is state-driven. So when the webhook arrives afterwards, layer
         * one waves it straight through: there is no event to collide with. Only
         * the compare-and-set is standing between it and a second CHARGE.
         */
        @Test
        void aWebhookArrivingAfterTheSweepSettledCannotChargeAgain() {
            String ref = initiated(LIVE_PAST_THE_SWEEP);
            gateway.remoteStatus(ref, PaymentGateway.RemoteStatus.SUCCESS);

            settlePayment.reconcile(NOW.plusSeconds(120));
            assertTrue(
                    payments.events.isEmpty(),
                    "the sweep must write no event, or this test proves nothing: "
                            + payments.events.keySet());

            var result = settlePayment.handle(success(ref), NOW.plusSeconds(121));

            var already = assertInstanceOf(SettlePayment.Result.AlreadySettled.class, result);
            assertEquals(PaymentStatus.SUCCESS, already.current());
            assertEquals(
                    List.of("CHARGE:" + FARE),
                    payments.ledger,
                    "a second CHARGE entry is a double charge, and FR-22's event key "
                            + "cannot prevent it here");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A booking held until {@code expiry}, with payment initiated. */
    private String initiated(Instant expiry) {
        long id = givenHeld(expiry);
        var result =
                assertInstanceOf(
                        InitiatePayment.Result.Initiated.class, initiatePayment.initiate(id, NOW));
        calls.clear();
        return result.reference();
    }

    private static SettlePayment.WebhookEvent success(String ref) {
        return new SettlePayment.WebhookEvent(
                ref, SettlePayment.EventType.PAYMENT_SUCCEEDED, "{\"status\":\"succeeded\"}");
    }

    private static SettlePayment.WebhookEvent failure(String ref) {
        return new SettlePayment.WebhookEvent(
                ref, SettlePayment.EventType.PAYMENT_FAILED, "{\"status\":\"failed\"}");
    }

    // ── fakes ───────────────────────────────────────────────────────────────

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

        FakeBookings(List<String> calls) {
            this.calls = calls;
        }

        long put(BookingStatus status, Instant holdExpiresAt) {
            long id = nextId++;
            rows.put(
                    id,
                    new BookingView(
                            id,
                            Optional.empty(),
                            status,
                            POOL,
                            RANGE,
                            1,
                            FARE,
                            900L,
                            Optional.ofNullable(holdExpiresAt),
                            BERTHS));
            return id;
        }

        BookingView get(long id) {
            return rows.get(id);
        }

        void expireHold(long id, Instant at) {
            replace(rows.get(id), rows.get(id).status(), Optional.of(at), rows.get(id).pnr());
        }

        private void replace(
                BookingView row,
                BookingStatus status,
                Optional<Instant> expiry,
                Optional<String> pnr) {
            rows.put(
                    row.id(),
                    new BookingView(
                            row.id(),
                            pnr,
                            status,
                            row.pool(),
                            row.range(),
                            row.passengerCount(),
                            row.farePaise(),
                            row.userId(),
                            expiry,
                            row.berthIds()));
        }

        private boolean transition(long id, BookingStatus from, BookingStatus to) {
            var row = rows.get(id);
            if (row == null || row.status() != from) {
                return false;
            }
            replace(row, to, row.holdExpiresAt(), row.pnr());
            return true;
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
        public boolean beginPayment(long bookingId, Instant at) {
            calls.add("bookings.beginPayment");
            return transition(bookingId, BookingStatus.HELD, BookingStatus.PAYMENT_PENDING);
        }

        @Override
        public boolean markFailed(long bookingId, Instant at) {
            calls.add("bookings.markFailed");
            return transition(
                    bookingId, BookingStatus.PAYMENT_PENDING, BookingStatus.FAILED);
        }

        @Override
        public boolean markFailedRefunded(long bookingId, Instant at) {
            calls.add("bookings.markFailedRefunded");
            return transition(
                    bookingId, BookingStatus.PAYMENT_PENDING, BookingStatus.FAILED_REFUNDED);
        }

        @Override
        public AllocationOutcome persistAllocations(
                long bookingId, long scheduleId, SegmentRange range, List<Long> berthIds) {
            calls.add("bookings.persistAllocations");
            return new AllocationOutcome.Persisted();
        }

        @Override
        public boolean confirm(long bookingId, String pnr, Instant confirmedAt) {
            calls.add("bookings.confirm");
            var row = rows.get(bookingId);
            if (row.status() != BookingStatus.PAYMENT_PENDING) {
                return false;
            }
            replace(row, BookingStatus.CONFIRMED, row.holdExpiresAt(), Optional.of(pnr));
            return true;
        }

        @Override
        public long createHeld(NewHeldBooking booking) {
            throw new UnsupportedOperationException("not part of the payment path");
        }

        @Override
        public int countActiveHolds(long userId, Instant now) {
            throw new UnsupportedOperationException("not part of the payment path");
        }
    }

    private static final class FakePayments implements PaymentRepository {
        private final List<String> calls;

        /**
         * FR-23 selects bookings in PAYMENT_PENDING. An earlier version of this
         * fake filtered on payment status alone and happily returned bookings
         * that were already CONFIRMED - which made the sweep look like it
         * re-examined settled work. A fake that does not honour its port's
         * contract tests the fake.
         */
        private final FakeBookings bookings;
        private final Map<String, PaymentRecord> byReference = new LinkedHashMap<>();
        final Map<String, String> events = new LinkedHashMap<>();
        final List<String> ledger = new ArrayList<>();
        private long nextId = 100;
        private long nextRefundId = 500;

        FakePayments(List<String> calls, FakeBookings bookings) {
            this.calls = calls;
            this.bookings = bookings;
        }

        PaymentRecord byReference(String reference) {
            return byReference.get(reference);
        }

        PaymentRecord only() {
            return byReference.values().iterator().next();
        }

        int count() {
            return byReference.size();
        }

        @Override
        public long create(NewPayment payment) {
            calls.add("payments.create");
            long id = nextId++;
            byReference.put(
                    payment.paymentReference(),
                    new PaymentRecord(
                            id,
                            payment.bookingId(),
                            payment.paymentReference(),
                            payment.amountPaise(),
                            PaymentStatus.INITIATED,
                            NOW,
                            Optional.empty()));
            return id;
        }

        @Override
        public boolean settle(String reference, PaymentStatus terminal, Instant settledAt) {
            calls.add("payments.settle");
            var existing = byReference.get(reference);
            if (existing == null || existing.status() != PaymentStatus.INITIATED) {
                return false;
            }
            byReference.put(
                    reference,
                    new PaymentRecord(
                            existing.id(),
                            existing.bookingId(),
                            reference,
                            existing.amountPaise(),
                            terminal,
                            existing.initiatedAt(),
                            Optional.of(settledAt)));
            return true;
        }

        @Override
        public boolean recordEvent(String reference, String eventType, String payload) {
            calls.add("payments.recordEvent");
            return events.putIfAbsent(reference + "/" + eventType, payload) == null;
        }

        @Override
        public List<PendingSettlement> findPendingSettlements(Instant cutoff, int limit) {
            calls.add("payments.findPendingSettlements");
            return byReference.values().stream()
                    .filter(p -> p.initiatedAt().isBefore(cutoff))
                    .filter(
                            p ->
                                    bookings.get(p.bookingId()).status()
                                            == BookingStatus.PAYMENT_PENDING)
                    .map(p -> new PendingSettlement(p.bookingId(), p))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<PaymentRecord> findByReference(String reference) {
            return Optional.ofNullable(byReference.get(reference));
        }

        @Override
        public Optional<PaymentRecord> findFor(long bookingId) {
            return byReference.values().stream().filter(p -> p.bookingId() == bookingId).findFirst();
        }

        @Override
        public Optional<PaymentRecord> findCapturedFor(long bookingId) {
            return findFor(bookingId).filter(p -> p.status() == PaymentStatus.SUCCESS);
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
            recordLedgerEntry(bookingId, LedgerEntryType.REFUND, amountPaise);
        }

        @Override
        public void failRefund(long refundId) {
            calls.add("payments.failRefund");
        }

        @Override
        public void recordLedgerEntry(long bookingId, LedgerEntryType type, long amountPaise) {
            ledger.add(type + ":" + amountPaise);
        }
    }

    private static final class FakeGateway implements PaymentGateway {
        private final List<String> calls;
        private final TrackingUnitOfWork unitOfWork;
        private final Map<String, RemoteStatus> remote = new HashMap<>();
        private ChargeOutcome nextCharge = new ChargeOutcome.Accepted();
        int charges;
        int refunds;
        int polls;
        int chargeTransactionDepth = -1;

        FakeGateway(List<String> calls, TrackingUnitOfWork unitOfWork) {
            this.calls = calls;
            this.unitOfWork = unitOfWork;
        }

        void declineCharges(String reason) {
            nextCharge = new ChargeOutcome.Rejected(reason);
        }

        void chargesUnreachable(String detail) {
            nextCharge = new ChargeOutcome.Unreachable(detail);
        }

        void remoteStatus(String reference, RemoteStatus status) {
            remote.put(reference, status);
        }

        @Override
        public ChargeOutcome charge(ChargeRequest request) {
            calls.add("gateway.charge");
            charges++;
            chargeTransactionDepth = unitOfWork.depth();
            return nextCharge;
        }

        @Override
        public RefundOutcome refund(RefundRequest request) {
            calls.add("gateway.refund");
            refunds++;
            return new RefundOutcome.Accepted();
        }

        @Override
        public RemoteStatus poll(String paymentReference) {
            calls.add("gateway.poll");
            polls++;
            return remote.getOrDefault(paymentReference, RemoteStatus.INITIATED);
        }
    }

    private static final class FixedReferences implements PaymentReferences {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public String next() {
            return "psp-ref-" + n.incrementAndGet();
        }
    }

    private static final class CountingSequence implements PnrSequence {
        private int issued;

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
