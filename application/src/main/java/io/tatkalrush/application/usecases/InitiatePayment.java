package io.tatkalrush.application.usecases;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.PaymentGateway;
import io.tatkalrush.application.ports.PaymentReferences;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.domain.booking.BookingStatus;
import java.time.Instant;

/**
 * {@code API-5}: start paying for a held booking (FR-21).
 *
 * <h2>Write the intent, then call the PSP — never the other way round</h2>
 *
 * <ol>
 *   <li>Lock the booking, verify the hold is live, insert a {@code payments} row
 *       as {@code INITIATED}, transition {@code HELD → PAYMENT_PENDING}. Commit.
 *   <li><b>Then</b> call the gateway.
 * </ol>
 *
 * <p>A crash between the two leaves a payment row with no charge behind it. FR-23
 * polls the PSP, hears {@code UNKNOWN}, and marks it failed — recoverable, and
 * nobody's money is involved.
 *
 * <p>Reverse the order and a crash leaves money captured with nothing in the
 * database pointing at it. INV-3 would report an orphaned payment and there would
 * be no booking to attach it to. That asymmetry is the whole argument: one
 * ordering fails into a recoverable state, the other into an unrecoverable one.
 *
 * <p>It only works because the reference is ours (see {@link PaymentReferences}).
 * A PSP-generated id cannot be written before the call that returns it.
 *
 * <h2>Idempotency without an idempotency key</h2>
 *
 * <p>FR-19's header is not required here. {@code HELD → PAYMENT_PENDING} is a
 * compare-and-set, so exactly one caller performs it and a second initiation finds
 * the booking already pending and returns the payment that exists. When the state
 * machine already admits one caller, a key would add a second thing to keep
 * consistent and no guarantee.
 *
 * <h2>Three gateway outcomes, three responses</h2>
 *
 * <ul>
 *   <li>{@code Accepted} — settlement is asynchronous (FR-53); wait for FR-22's
 *       webhook or FR-23's poll.
 *   <li>{@code Rejected} — the PSP declined. Nothing captured, so the booking goes
 *       to {@code FAILED}, not {@code FAILED_REFUNDED}: there is nothing to refund.
 *   <li>{@code Unreachable} — <b>unknown.</b> The charge may have landed. The
 *       payment stays {@code INITIATED} and FR-23 resolves it. Marking it failed
 *       here is the classic mistake and orphans real money.
 * </ul>
 */
public final class InitiatePayment {

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentReferences references;
    private final UnitOfWork unitOfWork;

    public InitiatePayment(
            BookingRepository bookings,
            PaymentRepository payments,
            PaymentGateway gateway,
            PaymentReferences references,
            UnitOfWork unitOfWork) {
        this.bookings = bookings;
        this.payments = payments;
        this.gateway = gateway;
        this.references = references;
        this.unitOfWork = unitOfWork;
    }

    public Result initiate(long bookingId, Instant now) {
        var preparation = unitOfWork.inTransaction(() -> prepare(bookingId, now));

        if (preparation instanceof Preparation.Refused refused) {
            return refused.result();
        }
        var p = (Preparation.Prepared) preparation;

        // Outside the transaction. FR-53's p99 is six seconds.
        var outcome =
                gateway.charge(
                        new PaymentGateway.ChargeRequest(
                                p.reference(), p.bookingId(), p.amountPaise()));

        return switch (outcome) {
            case PaymentGateway.ChargeOutcome.Accepted ignored ->
                    new Result.Initiated(p.bookingId(), p.paymentId(), p.reference(), p.amountPaise());

            case PaymentGateway.ChargeOutcome.Rejected rejected -> {
                unitOfWork.inTransaction(
                        () -> {
                            payments.settle(
                                    p.reference(), PaymentRepository.PaymentStatus.FAILED, now);
                            // FAILED, not FAILED_REFUNDED. Nothing was captured,
                            // so nothing is owed back.
                            bookings.markFailed(p.bookingId(), now);
                            return null;
                        });
                yield new Result.Declined(p.bookingId(), rejected.reason());
            }

            case PaymentGateway.ChargeOutcome.Unreachable unreachable ->
                    // Deliberately no state change. We do not know whether the
                    // charge landed, and the only safe answer to "I do not know"
                    // is to leave the record as it is and let FR-23 find out.
                    new Result.OutcomeUnknown(
                            p.bookingId(), p.reference(), unreachable.detail());
        };
    }

    private Preparation prepare(long bookingId, Instant now) {
        var maybeBooking = bookings.findByIdForUpdate(bookingId);
        if (maybeBooking.isEmpty()) {
            return refuse(new Result.UnknownBooking(bookingId));
        }
        var booking = maybeBooking.get();

        if (booking.status() == BookingStatus.PAYMENT_PENDING) {
            // A second initiation. Return what exists rather than charging again.
            return payments.findFor(bookingId)
                    .<Preparation>map(
                            existing ->
                                    refuse(
                                            new Result.AlreadyInitiated(
                                                    bookingId,
                                                    existing.id(),
                                                    existing.paymentReference(),
                                                    existing.amountPaise())))
                    // PAYMENT_PENDING with no payment row means the transition and
                    // the insert did not commit together, which the UnitOfWork
                    // contract forbids. Loud rather than silently re-charging.
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "booking %d is PAYMENT_PENDING with no payment row"
                                                    .formatted(bookingId)));
        }

        if (booking.status() != BookingStatus.HELD) {
            return refuse(new Result.NotPayable(bookingId, booking.status()));
        }

        var expiresAt =
                booking.holdExpiresAt()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "booking %d is HELD with no hold_expires_at"
                                                        .formatted(bookingId)));

        if (!expiresAt.isAfter(now)) {
            // §11.2's HOLD_EXPIRED, 410. Refusing here is cheaper than every
            // alternative: charging for a dead hold means capturing money we are
            // about to refund under FR-24, and manufacturing exactly the race C5
            // is supposed to measure honestly.
            return refuse(new Result.HoldExpired(bookingId, expiresAt));
        }

        String reference = references.next();
        long paymentId =
                payments.create(
                        new PaymentRepository.NewPayment(
                                bookingId, reference, booking.farePaise()));

        if (!bookings.beginPayment(bookingId, now)) {
            // Unreachable while the row lock above is held; if it ever fires, the
            // lock has been removed and this is the symptom.
            throw new IllegalStateException(
                    "lost HELD -> PAYMENT_PENDING for booking %d while holding its row lock"
                            .formatted(bookingId));
        }

        return new Preparation.Prepared(bookingId, paymentId, reference, booking.farePaise());
    }

    private static Preparation refuse(Result result) {
        return new Preparation.Refused(result);
    }

    /**
     * What the locked transaction concluded.
     *
     * <p>Separate from {@link Result} because a prepared payment is not yet an
     * outcome: the gateway has not been called, and it must not be called while
     * this transaction is open.
     */
    private sealed interface Preparation {

        /** Committed intent, ready for the gateway call. */
        record Prepared(long bookingId, long paymentId, String reference, long amountPaise)
                implements Preparation {}

        /** Settled without ever reaching the gateway. */
        record Refused(Result result) implements Preparation {}
    }

    public sealed interface Result {

        /** Charge accepted; settlement follows asynchronously (FR-53). */
        record Initiated(long bookingId, long paymentId, String reference, long amountPaise)
                implements Result {}

        /** Already paying. The existing payment, not a second charge. */
        record AlreadyInitiated(
                long bookingId, long paymentId, String reference, long amountPaise)
                implements Result {}

        /** {@code 402} — the PSP declined. Booking is {@code FAILED}. */
        record Declined(long bookingId, String reason) implements Result {}

        /**
         * The gateway did not answer. The payment stays {@code INITIATED} and
         * FR-23 will resolve it; the caller should poll rather than retry, because
         * retrying with a fresh reference would risk a second charge.
         */
        record OutcomeUnknown(long bookingId, String reference, String detail)
                implements Result {}

        /** {@code 410 HOLD_EXPIRED} — the hold lapsed before payment started. */
        record HoldExpired(long bookingId, Instant expiredAt) implements Result {}

        /** The booking is in a state that cannot be paid for (FR-27). */
        record NotPayable(long bookingId, BookingStatus status) implements Result {}

        /** {@code 404} — no such booking. */
        record UnknownBooking(long bookingId) implements Result {}
    }
}
