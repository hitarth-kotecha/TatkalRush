package io.tatkalrush.domain.pricing;

/**
 * Why money was returned (§10.3's {@code refunds.reason}).
 *
 * <p>This enum is a <b>diagnostic instrument</b>, not a label. Two of its four
 * values describe outcomes that are indistinguishable to a customer and opposite
 * to an engineer, and separating them is the entire reason the column exists
 * (DD-008).
 *
 * <ul>
 *   <li>{@link #HOLD_EXPIRED} — payment settled after the hold lapsed (FR-24).
 *       Benign, and <em>expected</em> during chaos scenarios C2 and C5. Seeing
 *       none of these during C5 would itself be suspicious.
 *   <li>{@link #ALLOCATION_CONFLICT} — the exclusion constraint rejected the
 *       allocation insert while the hold was still <em>live</em> (FR-25). A
 *       correct allocator cannot cause this. INV-11 asserts zero of them and a
 *       single one fails the run under NFR-9.
 * </ul>
 *
 * <p>Collapse those two into one value and the most serious bug the system can
 * have hides inside its most routine event, while every other §14 invariant
 * still reports green.
 */
public enum RefundReason {

    /** FR-44: a user cancelled a confirmed booking. Tiered refund. */
    CANCELLED,

    /** FR-42: still waitlisted when the chart was prepared. Full refund. */
    CHART_WL_REFUND,

    /** FR-24: payment succeeded after the hold expired. Benign. */
    HOLD_EXPIRED,

    /** FR-25/INV-11: an allocator defect, caught with money already captured. */
    ALLOCATION_CONFLICT;

    /**
     * Whether a refund for this reason means the software is broken.
     *
     * <p>Exactly one value returns {@code true}. The invariant checker asks this
     * question of the {@code refunds} table after every run (INV-11), and the
     * run fails if the answer is ever yes.
     */
    public boolean indicatesDefect() {
        return this == ALLOCATION_CONFLICT;
    }
}
