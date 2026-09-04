package io.tatkalrush.domain.booking;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Booking lifecycle states and the transitions between them (FR-27).
 *
 * <p>§6.4 calls the state diagram "the single source of truth for the Reviewer
 * agent" and says any transition not on it is a defect. Encoding it here rather
 * than in scattered {@code if} statements means an illegal transition fails where
 * it is attempted, naming the rule — instead of quietly producing a booking in a
 * state nothing downstream knows how to handle.
 *
 * <pre>
 *   HELD ──────────────────────────▶ EXPIRED        (hold lapses / user releases)
 *     │
 *     │ initiate payment
 *     ▼
 *   PAYMENT_PENDING ───────────────▶ FAILED         (PSP failure)
 *     │
 *     ├── hold live, rows inserted ─▶ CONFIRMED ───▶ CANCELLED
 *     │
 *     ├── hold already expired ─────▶ FAILED_REFUNDED
 *     └── allocation conflict ──────▶ FAILED_REFUNDED
 * </pre>
 *
 * <p><b>There is deliberately no {@code HELD → CANCELLED} edge</b> (FR-43).
 * Cancelling an unpaid hold is a <em>release</em> and lands on {@link #EXPIRED}.
 * The distinction is not pedantry: {@code CANCELLED} means a confirmed booking
 * that was paid for, and it triggers FR-44's refund tiers. A held booking has
 * taken no money, so routing it there would compute a refund against a payment
 * that never happened.
 *
 * <p><b>The two paths into {@link #FAILED_REFUNDED} share a state and differ in
 * meaning entirely.</b> One is FR-24's benign expiry race, expected under chaos
 * C2 and C5. The other is an allocator bug that fails the run (INV-11, NFR-9).
 * They are told apart by {@code refunds.reason}, not by the booking's state —
 * which is why that column exists (DD-008).
 */
public enum BookingStatus {

    /** Berths allocated, payment not yet started. Expires after FR-16's TTL. */
    HELD,

    /** Payment initiated with the PSP; the outcome is not yet known. */
    PAYMENT_PENDING,

    /** Money captured, allocation durable, PNR issued. */
    CONFIRMED,

    /** A confirmed booking cancelled by the user. Refund per FR-44/FR-45. */
    CANCELLED,

    /** A hold that lapsed, or was released before payment (FR-43). No money moved. */
    EXPIRED,

    /** Payment failed. Nothing was captured, so nothing is refunded. */
    FAILED,

    /** Money was captured and returned. See {@code refunds.reason} for which case. */
    FAILED_REFUNDED;

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED =
            new EnumMap<>(BookingStatus.class);

    static {
        ALLOWED.put(HELD, EnumSet.of(PAYMENT_PENDING, EXPIRED));
        ALLOWED.put(PAYMENT_PENDING, EnumSet.of(CONFIRMED, FAILED, FAILED_REFUNDED));
        ALLOWED.put(CONFIRMED, EnumSet.of(CANCELLED));
        // Terminal states. Listed explicitly rather than defaulted, so adding a
        // state to the enum without deciding its transitions fails a test rather
        // than silently becoming terminal.
        ALLOWED.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
        ALLOWED.put(EXPIRED, EnumSet.noneOf(BookingStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(BookingStatus.class));
        ALLOWED.put(FAILED_REFUNDED, EnumSet.noneOf(BookingStatus.class));
    }

    /** States reachable from here in one step. */
    public Set<BookingStatus> allowedTargets() {
        return Set.copyOf(ALLOWED.get(this));
    }

    public boolean canTransitionTo(BookingStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** True if no transition leaves this state. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Whether money was captured and is therefore owed back on cancellation. */
    public boolean moneyWasCaptured() {
        return this == CONFIRMED || this == CANCELLED || this == FAILED_REFUNDED;
    }

    /**
     * Asserts a transition is on the diagram.
     *
     * @throws IllegalStateException naming both states and what is legal from here
     */
    public void requireTransitionTo(BookingStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "illegal booking transition %s -> %s (FR-27). Legal from %s: %s"
                            .formatted(this, target, this, allowedTargets()));
        }
    }
}
