package io.tatkalrush.application.usecases;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.IntegrityAlarm;
import io.tatkalrush.application.ports.PaymentGateway;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.PnrSequence;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.booking.Pnr;
import io.tatkalrush.domain.pricing.RefundReason;
import java.time.Instant;
import java.util.List;

/**
 * Payment settled successfully — now decide whether the berth is still this
 * customer's (FR-24, FR-25).
 *
 * <p>One question, three answers, and two of them look identical to a customer
 * while being opposites to an engineer.
 *
 * <h2>FR-25's order, and why it is binding</h2>
 *
 * <ol>
 *   <li><b>Validate the hold is live</b> against {@code bookings.hold_expires_at}.
 *   <li><b>Insert {@code seat_allocations}</b>, subject to
 *       {@code no_overlapping_allocations}.
 *   <li><b>Transition to {@code CONFIRMED}</b> and issue a PNR.
 * </ol>
 *
 * <p>§6.4: "Steps 1 and 2 must not be reordered." Both orderings produce a
 * refund; only this one says <em>which kind</em>. Insert first and a constraint
 * rejection is ambiguous — the hold may have expired and the berth been
 * legitimately resold (benign, FR-24), or the hold may have been live and an
 * allocator handed one berth to two people (a defect that fails the run, INV-11).
 * Checking expiry first makes those cases mutually exclusive, so a constraint
 * trip carries exactly one meaning.
 *
 * <p>The tempting third option — treat every conflict as benign and retry — is
 * worse than either. It usually "works", and it converts a silent allocator
 * defect into a silent allocator defect plus a double charge, with every
 * dashboard still green.
 *
 * <h2>Expiry is read from Postgres, never Redis</h2>
 *
 * <p>FR-24 is explicit: chaos scenario C2 flushes Redis during P2, concurrently
 * with live payments. A decision about someone's money must not depend on a cache
 * being present.
 *
 * <h2>Transaction shape</h2>
 *
 * <p>Everything requiring the booking's row lock happens in <b>one</b>
 * transaction, including recording the intent to refund. The gateway call happens
 * <b>outside</b> it — FR-53 gives the PSP a p99 of six seconds, and a pooled
 * connection held across that call is how twenty connections come to serve three
 * requests a second. A third step records what the gateway actually did.
 *
 * <p>That ordering is also what makes a crash survivable: a {@code PENDING}
 * refund row written before the call is the evidence a retry needs. Calling first
 * and recording after loses the money silently, and INV-3 has no way to notice.
 */
public final class ConfirmBooking {

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PnrSequence pnrSequence;
    private final IntegrityAlarm alarm;
    private final UnitOfWork unitOfWork;

    public ConfirmBooking(
            BookingRepository bookings,
            PaymentRepository payments,
            PaymentGateway gateway,
            PnrSequence pnrSequence,
            IntegrityAlarm alarm,
            UnitOfWork unitOfWork) {
        this.bookings = bookings;
        this.payments = payments;
        this.gateway = gateway;
        this.pnrSequence = pnrSequence;
        this.alarm = alarm;
        this.unitOfWork = unitOfWork;
    }

    /**
     * @param now evaluated once and passed down, so step 1's expiry check and the
     *     timestamps written afterwards cannot disagree about what time it is
     */
    public Outcome confirm(long bookingId, Instant now) {
        var decision = unitOfWork.inTransaction(() -> decide(bookingId, now));

        if (decision instanceof Decision.RefundOpened opened) {
            return executeRefund(opened);
        }
        return ((Decision.Settled) decision).outcome();
    }

    private Decision decide(long bookingId, Instant now) {
        // FOR UPDATE, not a plain read. FR-22's webhook and FR-23's poll are two
        // routes to this same settlement and can arrive together; see
        // BookingRepository#findByIdForUpdate for what happens without the lock.
        var maybeBooking = bookings.findByIdForUpdate(bookingId);
        if (maybeBooking.isEmpty()) {
            return new Decision.Settled(new Outcome.UnknownBooking(bookingId));
        }
        var booking = maybeBooking.get();

        if (booking.status() != BookingStatus.PAYMENT_PENDING) {
            // Idempotent by construction: a redelivered webhook arriving after
            // confirmation finds CONFIRMED and stops here, without a second PNR,
            // a second allocation insert, or a second opinion about the money.
            return new Decision.Settled(
                    new Outcome.AlreadySettled(bookingId, booking.status()));
        }

        // ── FR-25 step 1 ────────────────────────────────────────────────────
        var expiresAt =
                booking.holdExpiresAt()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "booking %d is PAYMENT_PENDING with no hold_expires_at; FR-25 step 1 is unanswerable"
                                                        .formatted(bookingId)));

        if (!expiresAt.isAfter(now)) {
            // Benign (FR-24). Expected under chaos C2 and C5 - seeing none of
            // these during C5 would itself be evidence the scenario did nothing.
            // Note what does NOT happen here: no allocation insert is attempted,
            // which is precisely what keeps step 2's failure unambiguous.
            return openRefund(booking, RefundReason.HOLD_EXPIRED, now);
        }

        // ── FR-25 step 2 ────────────────────────────────────────────────────
        var allocation =
                bookings.persistAllocations(
                        bookingId,
                        booking.pool().scheduleId(),
                        booking.range(),
                        booking.berthIds());

        if (allocation instanceof BookingRepository.AllocationOutcome.Conflict conflict) {
            // The hold was verified live three statements ago. A correct
            // allocator cannot produce this. NFR-9 and INV-11 fail the run on it.
            //
            // Raised before the refund bookkeeping so the alarm still fires if
            // anything below throws - the run's verdict must not depend on the
            // cleanup succeeding.
            alarm.allocationConstraintViolated(bookingId, conflict.berthId());
            return openRefund(booking, RefundReason.ALLOCATION_CONFLICT, now);
        }

        // AlreadyPresent falls through deliberately. It means this booking's own
        // rows exist - a duplicate confirmation, not a double allocation - and the
        // compare-and-set below is the right arbiter: if the other caller already
        // transitioned, we lose it and report AlreadySettled.
        //
        // The repository finds this by querying before it inserts, because the
        // database genuinely cannot tell us: a duplicate's second insert overlaps
        // its own first rows and raises the very same 23P01 an allocator defect
        // raises. See BookingRepository#persistAllocations.

        // ── FR-25 step 3 ────────────────────────────────────────────────────
        var pnr = Pnr.fromSequence(pnrSequence.next());

        if (!bookings.confirm(bookingId, pnr.value(), now)) {
            return new Decision.Settled(
                    new Outcome.AlreadySettled(bookingId, BookingStatus.CONFIRMED));
        }

        // No CHARGE ledger entry is written here. Money was captured when the PSP
        // settled, before this class was called; the entry belongs there, or a
        // HOLD_EXPIRED refund would produce a REFUND entry with no matching
        // CHARGE and the ledger would not balance.
        return new Decision.Settled(
                new Outcome.Confirmed(bookingId, pnr.value(), booking.berthIds()));
    }

    /** Records the intent to refund, still under the booking's row lock. */
    private Decision openRefund(
            BookingRepository.BookingView booking, RefundReason reason, Instant now) {

        var payment =
                payments.findCapturedFor(booking.id())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "refusing to refund booking %d (%s): no captured payment. Refunding against an intent would send money that was never taken"
                                                        .formatted(booking.id(), reason)));

        long refundId =
                payments.openRefund(
                        booking.id(), payment.id(), payment.amountPaise(), reason);

        // The berth is definitively not this customer's from this moment,
        // independently of whether the money has come back yet. Leaving the
        // booking in PAYMENT_PENDING until the PSP answers would let FR-23's
        // reconciliation pick it up and try to settle it a second time.
        bookings.markFailedRefunded(booking.id(), now);

        return new Decision.RefundOpened(
                booking.id(), refundId, payment.id(), payment.paymentReference(),
                payment.amountPaise(), reason);
    }

    private Outcome executeRefund(Decision.RefundOpened opened) {
        // Outside the transaction. See the class comment.
        var result =
                gateway.refund(
                        new PaymentGateway.RefundRequest(
                                opened.paymentReference(),
                                opened.amountPaise(),
                                opened.reason().name()));

        var settlement = settlementOf(result);

        unitOfWork.inTransaction(
                () -> {
                    switch (settlement) {
                        case COMPLETED ->
                                payments.completeRefund(
                                        opened.refundId(),
                                        opened.paymentId(),
                                        opened.bookingId(),
                                        opened.amountPaise());
                        case FAILED -> payments.failRefund(opened.refundId());
                        case PENDING -> {
                            // Deliberately nothing. The row stays PENDING because
                            // that is the truth: the PSP may have processed the
                            // refund and lost the response. Marking it FAILED
                            // would assert money is still with the PSP when it
                            // might not be, and a retry would then double-refund.
                        }
                    }
                    return null;
                });

        return new Outcome.Refunded(
                opened.bookingId(),
                opened.refundId(),
                opened.amountPaise(),
                opened.reason(),
                settlement);
    }

    private static RefundSettlement settlementOf(PaymentGateway.RefundOutcome result) {
        return switch (result) {
            case PaymentGateway.RefundOutcome.Accepted ignored -> RefundSettlement.COMPLETED;
            // The PSP answered no. A human has to look at this; a retry will get
            // the same answer.
            case PaymentGateway.RefundOutcome.Rejected ignored -> RefundSettlement.FAILED;
            // No answer at all, which is not the same as "no". See PaymentGateway.
            case PaymentGateway.RefundOutcome.Unreachable ignored -> RefundSettlement.PENDING;
        };
    }

    /** What became of the refund attempt. */
    public enum RefundSettlement {
        /** The PSP accepted it; the ledger records the movement. */
        COMPLETED,
        /** The PSP declined it. Needs a human. */
        FAILED,
        /** Unknown. The {@code refunds} row stays {@code PENDING} for retry. */
        PENDING
    }

    /** Result of confirming (or declining to confirm) a settled payment. */
    public sealed interface Outcome {

        /** FR-25 completed. {@code PAYMENT_PENDING → CONFIRMED}, PNR issued. */
        record Confirmed(long bookingId, String pnr, List<Long> berthIds) implements Outcome {
            public Confirmed {
                berthIds = List.copyOf(berthIds);
            }
        }

        /**
         * Money returned. {@code PAYMENT_PENDING → FAILED_REFUNDED}.
         *
         * <p>The interesting field is {@code reason}: it is what separates FR-24's
         * expected expiry race from the allocator defect INV-11 fails the run on.
         */
        record Refunded(
                long bookingId,
                long refundId,
                long amountPaise,
                RefundReason reason,
                RefundSettlement settlement)
                implements Outcome {

            /** True only for {@code ALLOCATION_CONFLICT} (INV-11, NFR-9). */
            public boolean indicatesDefect() {
                return reason.indicatesDefect();
            }
        }

        /** Someone else already settled this booking. A no-op, not an error. */
        record AlreadySettled(long bookingId, BookingStatus status) implements Outcome {}

        /** No such booking. */
        record UnknownBooking(long bookingId) implements Outcome {}
    }

    /**
     * What the locked transaction concluded.
     *
     * <p>Separate from {@link Outcome} because a decision to refund is not yet an
     * outcome — the gateway has not been called, and it must not be called while
     * this transaction is open.
     */
    private sealed interface Decision {

        record Settled(Outcome outcome) implements Decision {}

        record RefundOpened(
                long bookingId,
                long refundId,
                long paymentId,
                String paymentReference,
                long amountPaise,
                RefundReason reason)
                implements Decision {}
    }
}
