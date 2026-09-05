package io.tatkalrush.application.usecases;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.PaymentGateway;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.PaymentRepository.PaymentStatus;
import io.tatkalrush.application.ports.UnitOfWork;
import java.time.Duration;
import java.time.Instant;

/**
 * The two routes by which a payment's outcome reaches us (FR-22, FR-23).
 *
 * <p>{@code API-6}'s webhook is the fast path. FR-23's reconciliation sweep is the
 * one that makes the system correct rather than usually-correct, because FR-54
 * lists <em>webhook-never-sent</em> as a configurable outcome: silence is normal,
 * not exceptional.
 *
 * <h2>Two layers of idempotency, because FR-22's is not sufficient on its own</h2>
 *
 * <ol>
 *   <li><b>{@code payment_events} unique key</b> on {@code (reference, event_type)}.
 *       Makes a redelivery of the same event a no-op — FR-55 double-delivers 5% of
 *       webhooks deliberately, and this is T-C5.
 *   <li><b>Compare-and-set on {@code payments.status}.</b> Makes <em>any</em>
 *       second settlement a no-op, whichever route it came by.
 * </ol>
 *
 * <p>FR-22's key alone would suffice if webhooks were the only route. FR-23 adds a
 * second one, and a poll carries no event, so it shares no key with a webhook and
 * the two would not dedupe against each other. The status transition is the one
 * point both must pass through.
 *
 * <h2>The routes dedupe on different things, on purpose</h2>
 *
 * <p>Webhooks are event-driven and dedupe on events. Reconciliation is state-driven
 * and never consults the event table to decide.
 *
 * <p>That asymmetry is load-bearing. Consider a crash between the settle commit and
 * confirmation: the payment is {@code SUCCESS}, the booking is still
 * {@code PAYMENT_PENDING}, and the {@code PAYMENT_SUCCEEDED} event row already
 * exists. An event-driven sweep would see "already handled" and skip — stranding a
 * captured payment on a booking that never advanced, forever. That is precisely
 * INV-3's violation, and driving the sweep from state instead makes it the repair.
 *
 * <h2>Out-of-order delivery</h2>
 *
 * <p>FR-22 admits it explicitly. A {@code FAILED} webhook arriving after a
 * {@code SUCCESS} finds the payment already terminal, loses the compare-and-set,
 * and changes nothing. The event is still recorded, because the audit trail should
 * show what the PSP said even when it said it too late.
 *
 * <h2>Known: the sweep is not partitioned between replicas</h2>
 *
 * <p>§8.3's stack runs two application instances, and both will run this sweep.
 * They will select the same rows. That is <b>safe but wasteful</b>: settlement is
 * a compare-and-set and confirmation takes the booking's row lock, so the second
 * replica settles nothing and confirms nothing — but it does call
 * {@link PaymentGateway#poll} for every payment the first one already handled,
 * doubling PSP load precisely during chaos C5, which is when the sweep has the
 * most to do and when its cost is being measured.
 *
 * <p>Not fixed here because the obvious fix does not fit the shape.
 * {@code FOR UPDATE SKIP LOCKED} partitions a queue between workers by holding the
 * lock for the length of the processing — and the processing here calls the PSP,
 * which must never happen inside a transaction. Doing it properly means claiming
 * rows in a short transaction (stamping a {@code last_reconciled_at}), committing,
 * and processing outside, which needs a column and a migration.
 *
 * <p>Recorded rather than done, because it is a cost, not a defect, and because
 * measuring it during P2 is a better basis for the design than guessing at it now.
 *
 * <h2>Where the HMAC check is not</h2>
 *
 * <p>FR-61's signature covers the raw request bytes and must be verified before
 * they are parsed — parsing unauthenticated input is itself the attack surface. It
 * therefore lives at the edge, in the web adapter, and this class receives an event
 * that has already been authenticated.
 */
public final class SettlePayment {

    /** How long a payment may sit unsettled before FR-23 goes looking. */
    public static final Duration DEFAULT_STALE_AFTER = Duration.ofSeconds(60);

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final ConfirmBooking confirmBooking;
    private final UnitOfWork unitOfWork;

    public SettlePayment(
            BookingRepository bookings,
            PaymentRepository payments,
            PaymentGateway gateway,
            ConfirmBooking confirmBooking,
            UnitOfWork unitOfWork) {
        this.bookings = bookings;
        this.payments = payments;
        this.gateway = gateway;
        this.confirmBooking = confirmBooking;
        this.unitOfWork = unitOfWork;
    }

    // ── FR-22: the webhook ──────────────────────────────────────────────────

    /**
     * What the PSP told us.
     *
     * @param type <b>our</b> enum, not the PSP's string. The dedup key is
     *     {@code (reference, event_type)}, so a provider renaming
     *     {@code payment.succeeded} to {@code payment_succeeded} would otherwise
     *     open a fresh bucket and the same event would settle twice. Mapping at the
     *     edge keeps the key ours, and an unrecognised type is rejected there rather
     *     than silently admitted here.
     * @param payload the raw body, stored for audit only. Never used to decide
     *     anything — dedup on a payload hash breaks the moment a provider adds a
     *     field or reformats its JSON.
     */
    public record WebhookEvent(String paymentReference, EventType type, String payload) {}

    public enum EventType {
        PAYMENT_SUCCEEDED(PaymentStatus.SUCCESS),
        PAYMENT_FAILED(PaymentStatus.FAILED);

        private final PaymentStatus terminal;

        EventType(PaymentStatus terminal) {
            this.terminal = terminal;
        }

        public PaymentStatus terminalStatus() {
            return terminal;
        }
    }

    public Result handle(WebhookEvent event, Instant now) {
        var applied =
                unitOfWork.inTransaction(
                        () -> {
                            // Insert-first (FR-22). Two concurrent redeliveries
                            // both blocking on the unique index is the point;
                            // a SELECT-then-INSERT lets both through.
                            //
                            // In the SAME transaction as the settlement below, so
                            // a crash rolls back both. Committing the dedup marker
                            // separately would make a redelivery a no-op for a
                            // settlement that never happened.
                            if (!payments.recordEvent(
                                    event.paymentReference(),
                                    event.type().name(),
                                    event.payload())) {
                                return new Applied.Duplicate();
                            }
                            return apply(
                                    event.paymentReference(), event.type().terminalStatus(), now);
                        });

        return complete(applied, now);
    }

    // ── FR-23: the reconciliation sweep ─────────────────────────────────────

    /**
     * @param settled payments the PSP had an answer for that we had not applied
     * @param confirmed bookings that reached {@code CONFIRMED}
     * @param refunded bookings whose hold had lapsed by the time the money arrived
     *     (FR-24). A sweep during chaos C5 should produce many of these, and a
     *     sweep that produces none during C5 means the scenario did nothing.
     * @param abandoned payments the PSP has never heard of — our crash between
     *     writing the intent and calling the gateway
     * @param stillPending the PSP is still working; FR-53's tail is six seconds
     */
    public record ReconciliationReport(
            int examined,
            int settled,
            int confirmed,
            int refunded,
            int abandoned,
            int stillPending) {}

    /**
     * Polls the PSP for every booking stuck in {@code PAYMENT_PENDING} (FR-23).
     *
     * <p>Driven entirely by current state. Nothing here asks whether an event was
     * received, which is what lets it repair the crash-after-settle case that an
     * event-driven sweep cannot see.
     */
    public ReconciliationReport reconcile(Instant now, Duration staleAfter, int batchSize) {
        var stale = payments.findPendingSettlements(now.minus(staleAfter), batchSize);

        int settled = 0;
        int confirmed = 0;
        int refunded = 0;
        int abandoned = 0;
        int pending = 0;

        for (var entry : stale) {
            var payment = entry.payment();
            Result result;

            if (payment.status() == PaymentStatus.SUCCESS) {
                // Money captured, booking never advanced: a crash between the
                // settle commit and confirmation. No poll needed - the PSP has
                // nothing to add, and this is a repair, not an enquiry.
                result = complete(new Applied.Settled(entry.bookingId(), payment), now);
            } else {
                var remote = gateway.poll(payment.paymentReference());
                if (remote == PaymentGateway.RemoteStatus.INITIATED) {
                    pending++;
                    continue;
                }
                if (remote == PaymentGateway.RemoteStatus.UNKNOWN) {
                    // The PSP has no record. We wrote the intent and then failed
                    // before the charge call landed, so no money moved and the
                    // booking can be failed outright. This is the case that makes
                    // "intent first, charge second" recoverable.
                    abandoned++;
                }
                var terminal =
                        remote == PaymentGateway.RemoteStatus.SUCCESS
                                ? PaymentStatus.SUCCESS
                                : PaymentStatus.FAILED;
                result = applyAndComplete(payment.paymentReference(), terminal, now);
                settled++;
            }

            // Counted from what confirmation actually decided, not from what the
            // PSP said. A successful payment against a lapsed hold is a refund
            // (FR-24), and reporting it as a confirmation would hide the case C5
            // is measuring.
            if (result instanceof Result.Settled s) {
                if (s.confirmation() instanceof ConfirmBooking.Outcome.Confirmed) {
                    confirmed++;
                } else if (s.confirmation() instanceof ConfirmBooking.Outcome.Refunded) {
                    refunded++;
                }
            }
        }

        return new ReconciliationReport(
                stale.size(), settled, confirmed, refunded, abandoned, pending);
    }

    // ── the shared settlement path ──────────────────────────────────────────

    private Result applyAndComplete(String reference, PaymentStatus terminal, Instant now) {
        var applied = unitOfWork.inTransaction(() -> apply(reference, terminal, now));
        return complete(applied, now);
    }

    /** Runs inside a transaction. Does not call the gateway or confirm anything. */
    private Applied apply(String reference, PaymentStatus terminal, Instant now) {
        var maybePayment = payments.findByReference(reference);
        if (maybePayment.isEmpty()) {
            return new Applied.Unknown(reference);
        }
        var payment = maybePayment.get();

        if (!payments.settle(reference, terminal, now)) {
            // Already terminal. An out-of-order FAILED after a SUCCESS lands here
            // and changes nothing, which is the correct response to being told
            // something true at the wrong time.
            return new Applied.Superseded(reference, payment.status());
        }

        if (terminal == PaymentStatus.SUCCESS) {
            // The CHARGE entry belongs HERE, where the money actually moved - not
            // at confirmation. Written at confirmation instead, a HOLD_EXPIRED
            // refund would produce a REFUND entry with no matching CHARGE and the
            // ledger would not balance for INV-2.
            payments.recordLedgerEntry(
                    payment.bookingId(),
                    PaymentRepository.LedgerEntryType.CHARGE,
                    payment.amountPaise());
            return new Applied.Settled(payment.bookingId(), payment);
        }

        // Nothing was captured, so FAILED rather than FAILED_REFUNDED.
        bookings.markFailed(payment.bookingId(), now);
        return new Applied.Failed(payment.bookingId());
    }

    /** Runs outside any transaction, because confirmation may call the PSP. */
    private Result complete(Applied applied, Instant now) {
        return switch (applied) {
            case Applied.Settled settled ->
                    new Result.Settled(
                            settled.bookingId(),
                            confirmBooking.confirm(settled.bookingId(), now));
            case Applied.Failed failed -> new Result.Failed(failed.bookingId());
            case Applied.Duplicate ignored -> new Result.DuplicateEvent();
            case Applied.Superseded superseded ->
                    new Result.AlreadySettled(superseded.reference(), superseded.current());
            case Applied.Unknown unknown -> new Result.UnknownPayment(unknown.reference());
        };
    }

    /** What the transaction did. Never leaves this class. */
    private sealed interface Applied {
        record Settled(long bookingId, PaymentRepository.PaymentRecord payment) implements Applied {}

        record Failed(long bookingId) implements Applied {}

        record Duplicate() implements Applied {}

        record Superseded(String reference, PaymentStatus current) implements Applied {}

        record Unknown(String reference) implements Applied {}
    }

    public sealed interface Result {

        /** Money captured; {@code confirmation} carries FR-25's verdict. */
        record Settled(long bookingId, ConfirmBooking.Outcome confirmation) implements Result {}

        /** The PSP declined. Booking is {@code FAILED}; nothing to refund. */
        record Failed(long bookingId) implements Result {}

        /** T-C5: this exact event was already recorded. A no-op, and a 200. */
        record DuplicateEvent() implements Result {}

        /** A different route settled this first, or the event arrived late. */
        record AlreadySettled(String reference, PaymentStatus current) implements Result {}

        /**
         * No payment with that reference.
         *
         * <p>Answered with a 200, not a 404: a PSP that receives an error retries,
         * and retrying will not conjure a payment we have no record of. It is
         * counted, because a nonzero rate means references are being lost.
         */
        record UnknownPayment(String reference) implements Result {}
    }

    /** Convenience for the scheduled job (FR-23 runs every 30 seconds). */
    public ReconciliationReport reconcile(Instant now) {
        return reconcile(now, DEFAULT_STALE_AFTER, 200);
    }
}
