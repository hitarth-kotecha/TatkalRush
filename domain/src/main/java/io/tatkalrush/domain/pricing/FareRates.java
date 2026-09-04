package io.tatkalrush.domain.pricing;

import io.tatkalrush.domain.inventory.TravelClass;
import java.util.Map;

/**
 * The per-class rate table (FR-67, FR-68).
 *
 * <p><b>Checked in, not configured.</b> FR-67 calls this "a checked-in constant
 * table" and the reason is FR-67a: fare is frozen onto the booking at hold time
 * and never recomputed, while INV-7 recomputes the expected value independently
 * from these rates. If they lived in a database row or a config file, editing one
 * would silently change the expected fare for every historical booking and break
 * INV-7 across the entire dataset at once — with the invariant reporting a
 * pricing bug that is really a deployment event.
 *
 * <p>Changing a rate is therefore a code change, a commit, and a decision-log
 * entry. That is the intended friction.
 *
 * <p>All values are <b>paise</b>. Never rupees, never floating point: INV-7
 * compares a recomputed value against a stored one for exact equality, and binary
 * floating point makes that comparison fail intermittently on amounts that look
 * exactly representable.
 */
public final class FareRates {

    /**
     * Rate per kilometre, base fare, and TATKAL surcharge, per class.
     *
     * <p>Ordered SL cheapest through 1A dearest, as FR-67 requires. The 3A row
     * matches the worked example in §6.9 exactly — 285 paise/km and a 4,000 paise
     * base — so that example is executable rather than illustrative.
     *
     * <p>Ratios follow real Indian Railways pricing closely enough to be
     * defensible under questioning; they are not scraped from a live tariff,
     * which NG-5 puts out of scope.
     */
    private static final Map<TravelClass, Rates> TABLE =
            Map.of(
                    TravelClass.SL, new Rates(75, 1_500, 10_000),
                    TravelClass.CC, new Rates(180, 2_500, 12_500),
                    TravelClass.AC3, new Rates(285, 4_000, 30_000),
                    TravelClass.AC2, new Rates(420, 6_000, 40_000),
                    TravelClass.AC1, new Rates(720, 10_000, 50_000));

    /**
     * @param paisePerKm distance component, FR-67
     * @param basePaise flat component, FR-67
     * @param tatkalSurchargePaise flat per-passenger TATKAL premium, FR-68. This
     *     is what gives FR-45's no-refund rule something material to bite on: a
     *     Tatkal booking is meaningfully dearer, and cancelling it returns
     *     nothing.
     */
    public record Rates(long paisePerKm, long basePaise, long tatkalSurchargePaise) {}

    private FareRates() {}

    public static Rates forClass(TravelClass travelClass) {
        Rates rates = TABLE.get(travelClass);
        if (rates == null) {
            // Unreachable while TravelClass and TABLE agree - which is exactly
            // why it must throw rather than default. A silently-zero fare would
            // pass every test that does not assert an amount, and INV-7 would
            // then report the pricing bug as a data mismatch.
            throw new IllegalArgumentException("no fare rates configured for " + travelClass);
        }
        return rates;
    }
}
