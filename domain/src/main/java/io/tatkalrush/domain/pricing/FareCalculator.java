package io.tatkalrush.domain.pricing;

import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fare computation (FR-67, FR-68).
 *
 * <pre>
 *   fare_paise = ceil(distance_km x class_rate_paise_per_km) + class_base_paise
 * </pre>
 *
 * <p><b>A pure function, and that is a requirement rather than a preference.</b>
 * FR-67b says INV-7 must recompute the expected fare <em>independently</em> —
 * from {@code (distance, class, quota, cancelled_at, departure_time)} and never
 * by reading {@code bookings.fare_paise}. Comparing a stored value against itself
 * is a tautology that passes while pricing is wrong. So this performs no I/O and
 * holds no state: the invariant checker can call it with no database and no
 * container.
 *
 * <p><b>Computed once, then frozen</b> (FR-67a). The result is written to
 * {@code bookings.fare_paise} at hold time and never recomputed at confirm,
 * cancel or chart. Recomputing would let a single rate-table edit change the
 * expected value for every historical booking at once, breaking INV-7 across the
 * whole dataset and reporting a deployment as a pricing defect.
 */
public final class FareCalculator {

    private FareCalculator() {}

    /**
     * Total fare for a booking, in paise.
     *
     * @param distanceKm summed over {@code train_stops.distance_km} across the
     *     journey's segments {@code [from_seq, to_seq)}
     * @param passengerCount FR-67's fare is per passenger; FR-68's surcharge is
     *     explicitly per passenger too
     */
    public static long farePaise(
            BigDecimal distanceKm,
            TravelClass travelClass,
            QuotaType quotaType,
            int passengerCount) {

        if (distanceKm == null || distanceKm.signum() < 0) {
            // A negative distance means train_stops.distance_km is not monotonic,
            // which the seed generator guarantees and a test asserts. Failing
            // loudly here beats emitting a negative fare that INV-7 would later
            // report as a mismatch, pointing at pricing rather than at the data.
            throw new IllegalArgumentException("distanceKm must be non-negative, got " + distanceKm);
        }
        if (passengerCount < 1) {
            throw new IllegalArgumentException(
                    "passengerCount must be >= 1, got " + passengerCount);
        }

        return perPassengerPaise(distanceKm, travelClass, quotaType) * passengerCount;
    }

    /** One passenger's fare, including any TATKAL surcharge. */
    public static long perPassengerPaise(
            BigDecimal distanceKm, TravelClass travelClass, QuotaType quotaType) {

        var rates = FareRates.forClass(travelClass);

        // ceil, not round (FR-67). With NUMERIC(7,2) distances the product carries
        // fractional paise - 730.25 km x 285 is 208,121.25 - and rounding down
        // would undercharge on every such booking. More importantly INV-7
        // recomputes with this same expression, so the two must round identically
        // or every comparison fails.
        //
        // BigDecimal throughout: doing this in double puts a binary floating
        // point value into a ceil() at an exact-integer boundary, which lands on
        // either side depending on accumulated error and produces INV-7 failures
        // that do not reproduce.
        long distanceComponent =
                distanceKm
                        .multiply(BigDecimal.valueOf(rates.paisePerKm()))
                        .setScale(0, RoundingMode.CEILING)
                        .longValueExact();

        long fare = distanceComponent + rates.basePaise();

        // FR-68. Real IRCTC behaviour, and what makes FR-45's no-refund rule
        // material: a Tatkal booking costs meaningfully more and returns nothing
        // when cancelled.
        if (quotaType == QuotaType.TATKAL) {
            fare += rates.tatkalSurchargePaise();
        }
        return fare;
    }
}
