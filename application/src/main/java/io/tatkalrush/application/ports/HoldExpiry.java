package io.tatkalrush.application.ports;

import java.time.Instant;

/**
 * The durable half of FR-18: bookings whose hold lapsed become {@code EXPIRED}.
 *
 * <p>§12's failure table gives the reaper two effects — "release berths, booking →
 * {@code EXPIRED}" — and for its whole life this system had neither in the
 * background and only the first lazily. A lapsed {@code HELD} row stayed
 * {@code HELD} indefinitely: the customer's booking page said "held", FR-20's count
 * was right only because it filters on expiry, and the partial index V4 created
 * "to drive the hold reaper" had never been read by one.
 *
 * <p>Its own port rather than a method on {@link BookingRepository}, because only
 * the reaper needs it. Four hand-written fakes implement that interface for use
 * cases that never expire anything, and each would have grown a method it stubs.
 */
public interface HoldExpiry {

    /**
     * {@code HELD → EXPIRED} for up to {@code limit} bookings whose hold expired at
     * or before {@code now}, clearing {@code hold_expires_at} (INV-10).
     *
     * <p>Inclusive, like every other expiry decision in the system: the lazy reap,
     * {@code InitiatePayment}'s check and FR-20's active-hold count all treat
     * {@code expiresAt <= now} as lapsed.
     *
     * <p>{@code PAYMENT_PENDING} is never touched, whatever its expiry. Money may be
     * moving, and FR-23's reconciliation and FR-24's refund are what decide that
     * booking's fate.
     *
     * <p>A row another transaction has locked is skipped, not waited for. The
     * likeliest locker is {@code InitiatePayment} moving that very booking to
     * {@code PAYMENT_PENDING}; if it wins, there is nothing to expire, and if it
     * loses, the next sweep finds the row.
     *
     * @return bookings expired; fewer than {@code limit} means none remain
     */
    int expireLapsed(Instant now, int limit);
}
