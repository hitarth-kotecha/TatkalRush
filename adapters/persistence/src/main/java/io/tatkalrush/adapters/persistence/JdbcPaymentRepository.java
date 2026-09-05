package io.tatkalrush.adapters.persistence;

import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.domain.pricing.RefundReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link PaymentRepository} on Postgres.
 *
 * <p>Two statements here carry guarantees rather than data:
 *
 * <ul>
 *   <li>{@link #settle} is a compare-and-set, and it is the layer that makes
 *       settlement exactly-once <b>across routes</b> (DD-034). FR-22's
 *       {@code (payment_id, event_type)} key cannot dedupe a webhook against
 *       FR-23's poll, because a poll carries no event.
 *   <li>{@link #recordEvent} is insert-first, for the same reason FR-19's
 *       idempotency store is: two concurrent redeliveries must block on the index,
 *       not both read an empty table and both proceed.
 * </ul>
 */
public final class JdbcPaymentRepository implements PaymentRepository {

    private final JdbcClient jdbc;

    public JdbcPaymentRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    // ── the intent ──────────────────────────────────────────────────────────

    @Override
    public long create(NewPayment payment) {
        // psp_payment_id is UNIQUE, so a replayed initiation blocks on the index
        // rather than opening a second charge. The reference is ours (see
        // PaymentReferences), which is what lets this row exist before the
        // gateway call rather than after it.
        return jdbc.sql(
                        """
                        INSERT INTO payments (booking_id, psp_payment_id, amount_paise, status)
                        VALUES (?, ?, ?, 'INITIATED')
                        RETURNING id
                        """)
                .param(payment.bookingId())
                .param(payment.paymentReference())
                .param(payment.amountPaise())
                .query(Long.class)
                .single();
    }

    // ── reads ───────────────────────────────────────────────────────────────

    private static final String SELECT_PAYMENT =
            """
            SELECT id, booking_id, psp_payment_id, amount_paise, status,
                   initiated_at, settled_at
            FROM payments
            """;

    @Override
    public Optional<PaymentRecord> findByReference(String paymentReference) {
        return jdbc.sql(SELECT_PAYMENT + " WHERE psp_payment_id = ?")
                .param(paymentReference)
                .query(JdbcPaymentRepository::toRecord)
                .optional();
    }

    @Override
    public Optional<PaymentRecord> findFor(long bookingId) {
        return jdbc.sql(SELECT_PAYMENT + " WHERE booking_id = ? ORDER BY id DESC LIMIT 1")
                .param(bookingId)
                .query(JdbcPaymentRepository::toRecord)
                .optional();
    }

    @Override
    public Optional<PaymentRecord> findCapturedFor(long bookingId) {
        // SUCCESS or REFUNDED, never INITIATED. A payment row exists from the
        // moment we intended to charge, and refunding against an intent would
        // send money that was never taken. REFUNDED is included so a retry of a
        // half-finished refund can still find the payment it belongs to.
        return jdbc.sql(
                        SELECT_PAYMENT
                                + " WHERE booking_id = ? AND status IN ('SUCCESS', 'REFUNDED')"
                                + " ORDER BY id DESC LIMIT 1")
                .param(bookingId)
                .query(JdbcPaymentRepository::toRecord)
                .optional();
    }

    private static PaymentRecord toRecord(ResultSet rs, int rowNum) throws SQLException {
        Timestamp settled = rs.getTimestamp("settled_at");
        return new PaymentRecord(
                rs.getLong("id"),
                rs.getLong("booking_id"),
                rs.getString("psp_payment_id"),
                rs.getLong("amount_paise"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("initiated_at").toInstant(),
                Optional.ofNullable(settled).map(Timestamp::toInstant));
    }

    // ── settlement ──────────────────────────────────────────────────────────

    @Override
    public boolean settle(String paymentReference, PaymentStatus terminal, Instant settledAt) {
        int updated =
                jdbc.sql(
                                """
                                UPDATE payments
                                SET status = ?, settled_at = ?
                                WHERE psp_payment_id = ? AND status = 'INITIATED'
                                """)
                        .param(terminal.name())
                        .param(Timestamp.from(settledAt))
                        .param(paymentReference)
                        .update();

        // Zero rows means someone settled it first - a redelivered webhook, or
        // FR-23's sweep overtaking a slow one. A no-op, not an error.
        return updated == 1;
    }

    @Override
    public boolean recordEvent(String paymentReference, String eventType, String payload) {
        // ?::jsonb, not ?. payload is a JSONB column and the driver sends a type
        // with every parameter; Postgres will not implicitly cast character
        // varying to jsonb, and the error it raises names the COLUMN, which sends
        // a reader to the schema rather than to the binding.
        int inserted =
                jdbc.sql(
                                """
                                INSERT INTO payment_events (psp_payment_id, event_type, payload)
                                VALUES (?, ?, ?::jsonb)
                                ON CONFLICT (psp_payment_id, event_type) DO NOTHING
                                """)
                        .param(paymentReference)
                        .param(eventType)
                        .param(payload)
                        .update();

        // DO NOTHING rather than catching a unique violation, matching
        // JdbcIdempotencyStore. The conflicting statement still takes the index
        // lock and still waits for the incumbent transaction - which is the
        // property that makes this correct under FR-55's deliberate double
        // delivery - but returns zero rows instead of aborting the transaction
        // the settlement is about to continue in.
        return inserted == 1;
    }

    @Override
    public List<PendingSettlement> findPendingSettlements(Instant cutoff, int limit) {
        // Selected by STATE - bookings still PAYMENT_PENDING - never by the
        // absence of an event row. A crash between settling the payment and
        // confirming the booking leaves the PAYMENT_SUCCEEDED event already
        // written, so an event-driven sweep would skip exactly the case it exists
        // to repair (DD-034).
        //
        // Note for operators: this is not partitioned between replicas. Two app
        // instances running the sweep will examine the same rows. That is SAFE -
        // settle() is a compare-and-set and confirmation takes a row lock - but it
        // doubles the PSP polling during a sweep. See the note in SettlePayment.
        return jdbc.sql(
                        """
                        SELECT p.id, p.booking_id, p.psp_payment_id, p.amount_paise,
                               p.status, p.initiated_at, p.settled_at
                        FROM payments p
                        JOIN bookings b ON b.id = p.booking_id
                        WHERE b.status = 'PAYMENT_PENDING'
                          AND p.initiated_at < ?
                        ORDER BY p.initiated_at
                        LIMIT ?
                        """)
                .param(Timestamp.from(cutoff))
                .param(limit)
                .query(
                        (ResultSet rs, int rowNum) ->
                                new PendingSettlement(rs.getLong("booking_id"), toRecord(rs, rowNum)))
                .list();
    }

    // ── refunds and the ledger ──────────────────────────────────────────────

    @Override
    public long openRefund(long bookingId, long paymentId, long amountPaise, RefundReason reason) {
        // PENDING, written BEFORE the gateway is called. If the process dies
        // during that call this row is the evidence a retry works from; recording
        // afterwards instead loses the money silently and INV-3 cannot notice.
        return jdbc.sql(
                        """
                        INSERT INTO refunds (booking_id, payment_id, amount_paise, reason, status)
                        VALUES (?, ?, ?, ?, 'PENDING')
                        RETURNING id
                        """)
                .param(bookingId)
                .param(paymentId)
                .param(amountPaise)
                .param(reason.name())
                .query(Long.class)
                .single();
    }

    @Override
    public void completeRefund(long refundId, long paymentId, long bookingId, long amountPaise) {
        jdbc.sql("UPDATE refunds SET status = 'COMPLETED' WHERE id = ?").param(refundId).update();

        jdbc.sql("UPDATE payments SET status = 'REFUNDED' WHERE id = ?").param(paymentId).update();

        // The REFUND entry is written only here, where the money actually came
        // back. Its matching CHARGE was written at settlement for the same
        // reason - if either moved, INV-2 could not balance the ledger.
        recordLedgerEntry(bookingId, LedgerEntryType.REFUND, amountPaise);
    }

    @Override
    public void failRefund(long refundId) {
        // The payment deliberately stays SUCCESS. Money is still with the PSP,
        // and marking it REFUNDED would make the ledger assert a movement that
        // did not happen.
        jdbc.sql("UPDATE refunds SET status = 'FAILED' WHERE id = ?").param(refundId).update();
    }

    @Override
    public void recordLedgerEntry(long bookingId, LedgerEntryType type, long amountPaise) {
        jdbc.sql(
                        """
                        INSERT INTO ledger_entries (booking_id, entry_type, amount_paise)
                        VALUES (?, ?, ?)
                        """)
                .param(bookingId)
                .param(type.name())
                .param(amountPaise)
                .update();
    }
}
