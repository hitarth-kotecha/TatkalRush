package io.tatkalrush.domain.pricing;

import io.tatkalrush.domain.inventory.QuotaType;
import java.time.Duration;
import java.time.Instant;

/**
 * Refund rules (FR-44, FR-45, FR-46).
 *
 * <p>A deliberately simplified three-tier rule; the full IRCTC slab matrix is out
 * of scope (NG-4). Pure, for the same reason {@link FareCalculator} is: INV-7
 * recomputes the expected <em>retained</em> fare independently and must not read
 * what the booking stored (FR-67b).
 */
public final class RefundPolicy {

    private RefundPolicy() {}

    /** FR-44's tiers, by time remaining before departure. */
    public enum Tier {
        /** More than 48 h before departure: 90% back. */
        EARLY(90),
        /** Between 12 and 48 h: 50% back. */
        STANDARD(50),
        /** Under 12 h, or after departure: nothing. */
        LATE(0);

        private final int percent;

        Tier(int percent) {
            this.percent = percent;
        }

        public int percent() {
            return percent;
        }
    }

    /**
     * Refund due when a confirmed booking is cancelled by the user.
     *
     * @param farePaise the frozen {@code bookings.fare_paise} (FR-67a)
     * @param quotaType TATKAL is refused outright (FR-45)
     */
    public static long refundOnCancellation(
            long farePaise, QuotaType quotaType, Instant cancelledAt, Instant departureAt) {

        requireNonNegative(farePaise);

        // FR-45: confirmed TATKAL bookings receive NO refund, regardless of
        // window. A real IRCTC rule, and the reason FR-68's surcharge matters -
        // a Tatkal passenger pays a premium and forfeits all of it.
        //
        // Checked before the tier, not after: at 72 hours out the EARLY tier
        // would otherwise return 90% of a fare that is never refundable.
        if (quotaType == QuotaType.TATKAL) {
            return 0;
        }

        return applyPercent(farePaise, tierFor(cancelledAt, departureAt).percent());
    }

    /**
     * FR-46: a waitlisted booking still unconfirmed at chart preparation is
     * refunded in full, overriding FR-44's tiers.
     *
     * <p>Separate method rather than a flag, because the two are different events
     * with different causes. This one is the railway failing to seat a passenger;
     * {@link #refundOnCancellation} is a passenger changing their mind. Collapsing
     * them would also blur {@code refunds.reason}, which INV-11 depends on to tell
     * a benign event from an allocator bug.
     */
    public static long refundOnChartWaitlist(long farePaise) {
        requireNonNegative(farePaise);
        return farePaise;
    }

    /** FR-44's tier for a cancellation instant. */
    public static Tier tierFor(Instant cancelledAt, Instant departureAt) {
        // A cancellation after departure is LATE, not an error. Clocks skew, a
        // webhook can be slow, and a chaos run deliberately reorders events -
        // throwing here would turn an awkward-but-real case into a failed
        // request. Duration goes negative and falls through to LATE naturally.
        Duration remaining = Duration.between(cancelledAt, departureAt);

        if (remaining.compareTo(Duration.ofHours(48)) > 0) {
            return Tier.EARLY;
        }
        if (remaining.compareTo(Duration.ofHours(12)) >= 0) {
            return Tier.STANDARD;
        }
        return Tier.LATE;
    }

    /**
     * Percentage of an integer paise amount, rounded <b>down</b>.
     *
     * <p>Rounding down favours the railway on the fraction of a paise, which is
     * the conventional direction and — more usefully — is deterministic. INV-7
     * recomputes the retained amount with this same expression, so what matters
     * most is that both sides round identically.
     */
    private static long applyPercent(long farePaise, int percent) {
        return farePaise * percent / 100;
    }

    private static void requireNonNegative(long farePaise) {
        if (farePaise < 0) {
            throw new IllegalArgumentException("farePaise must be non-negative, got " + farePaise);
        }
    }
}
