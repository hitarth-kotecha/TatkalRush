package io.tatkalrush.application.ports;

import java.util.List;

/**
 * Outcome of promoting a hold into a durable confirmed allocation (FR-25).
 *
 * <p>The three cases are separated because they demand three different responses,
 * and two of them are routinely confused:
 *
 * <ul>
 *   <li>{@link Confirmed} — money captured, allocation durable.
 *   <li>{@link HoldExpired} — <b>benign.</b> Payment succeeded after the hold
 *       lapsed (FR-24). Expected during chaos C2 and C5. Auto-refund with reason
 *       {@code HOLD_EXPIRED}.
 *   <li>{@link AllocationConflict} — <b>a bug.</b> The exclusion constraint
 *       rejected the insert while the hold was still <em>live</em>, which a
 *       correct allocator can never cause. Auto-refund with reason
 *       {@code ALLOCATION_CONFLICT}, increment
 *       {@code allocation_constraint_violations_total}, and <b>fail the run</b>
 *       under NFR-9 and INV-11.
 * </ul>
 *
 * <p>Without that last distinction the constraint converts a data bug into a
 * money bug while every §14 invariant still reports green (DD-008).
 */
public sealed interface ConfirmResult {

    /** @param berthIds the berths now durably allocated to the booking */
    record Confirmed(long bookingId, List<Long> berthIds) implements ConfirmResult {
        public Confirmed {
            berthIds = List.copyOf(berthIds);
        }
    }

    /** Benign: the hold lapsed before payment settled (FR-24). */
    record HoldExpired(String holdId) implements ConfirmResult {}

    /**
     * An allocator defect reached production, and the customer has already paid.
     *
     * @param conflictingBerthId the berth the constraint refused
     */
    record AllocationConflict(String holdId, long conflictingBerthId) implements ConfirmResult {}
}
