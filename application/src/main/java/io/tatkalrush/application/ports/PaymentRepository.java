package io.tatkalrush.application.ports;

import io.tatkalrush.domain.pricing.RefundReason;
import java.time.Instant;
import java.util.Optional;

/** Persistence for payments, refunds and the ledger (§10.3). */
public interface PaymentRepository {

    /** Mirrors {@code payments.status}'s CHECK constraint. */
    enum PaymentStatus {
        INITIATED,
        SUCCESS,
        FAILED,
        REFUNDED
    }

    /** Mirrors {@code ledger_entries.entry_type}'s CHECK constraint. */
    enum LedgerEntryType {
        CHARGE,
        REFUND
    }

    record PaymentRecord(
            long id,
            long bookingId,
            String paymentReference,
            long amountPaise,
            PaymentStatus status,
            Instant initiatedAt,
            Optional<Instant> settledAt) {}

    /**
     * The captured payment for a booking, if money has actually moved.
     *
     * <p>Returns empty for a payment still {@code INITIATED}. Refund logic asks
     * this question rather than "is there a payment row", because a payment row
     * exists from the moment we intended to charge — refunding against intent
     * would send money we never took.
     */
    Optional<PaymentRecord> findCapturedFor(long bookingId);

    /**
     * Records the <b>intention</b> to refund, before the PSP is called.
     *
     * <p>Writes a {@code refunds} row with {@code status = 'PENDING'} and returns
     * its id. If the process dies during the gateway call, this row is the
     * evidence a retry needs; the alternative ordering — call first, record after
     * — loses the money silently and INV-3 has no way to notice.
     *
     * @return the new refund's id
     */
    long openRefund(long bookingId, long paymentId, long amountPaise, RefundReason reason);

    /**
     * Marks a refund {@code COMPLETED}, sets the payment to {@code REFUNDED} and
     * writes the {@code REFUND} ledger entry — as one transaction's worth of work.
     */
    void completeRefund(long refundId, long paymentId, long bookingId, long amountPaise);

    /**
     * Marks a refund {@code FAILED}.
     *
     * <p>The payment deliberately stays {@code SUCCESS}: money is still with the
     * PSP, and recording it as {@code REFUNDED} would make the ledger claim a
     * movement that did not happen.
     */
    void failRefund(long refundId);

    void recordLedgerEntry(long bookingId, LedgerEntryType type, long amountPaise);
}
