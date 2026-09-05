package io.tatkalrush.application.ports;

/**
 * Source of PNR sequence values (FR-26).
 *
 * <p>A port of its own, rather than a method on {@code BookingRepository},
 * because the guarantee it carries is narrow and specific: <b>every call returns
 * a value no other call has returned</b>, under any concurrency, without
 * coordination by the caller. That is a property of a Postgres sequence, and
 * naming it separately makes it hard to accidentally reimplement as a
 * {@code SELECT max(pnr) + 1}, which is the same bug FR-26 forbids in its other
 * form.
 *
 * <p>Sequence values are consumed on rollback too — Postgres deliberately does
 * not roll them back, because doing so would serialise every writer on the
 * sequence. Gaps in issued PNRs are therefore expected and are not evidence of
 * lost bookings.
 */
public interface PnrSequence {

    /** @return the next value, 1..999,999,999 (see {@code Pnr.fromSequence}) */
    long next();
}
