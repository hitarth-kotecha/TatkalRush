package io.tatkalrush.ops.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-9: {@code TATKAL} pool size is {@code ceil(0.10 x class_capacity)}, minimum
 * 1 berth.
 *
 * <p>This test exists because Phase 0 got it wrong three ways in a single line —
 * 0.20 instead of 0.10, {@code round} instead of {@code ceil}, and no floor — and
 * recorded the result in DD-026 as a decision rather than reading the
 * requirement. Nothing caught it: the pools were disjoint, the totals were right,
 * and every Phase 0 assertion passed.
 *
 * <p>The consequence was not cosmetic. The TATKAL pool is exactly what P1's spike
 * contends over, so a pool of twice the mandated size halves the contention that
 * §9.4 exists to measure. See DD-030.
 */
class TatkalPoolSizeTest {

    @ParameterizedTest
    @CsvSource({
        // capacity, expected TATKAL berths
        "72, 8", // sleeper coach: ceil(7.2)
        "64, 7", // 3A:            ceil(6.4)
        "48, 5", // 2A:            ceil(4.8)
        "24, 3", // 1A:            ceil(2.4)
        "100, 10", // exact multiple, no rounding involved
        "10, 1",
        "1, 1",
    })
    @DisplayName("FR-9: ceil(0.10 x capacity)")
    void matchesFr9(int capacity, int expected) {
        assertEquals(expected, SeedGenerator.tatkalPoolSize(capacity));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 9})
    @DisplayName("FR-9's floor: a small class still gets one TATKAL berth")
    void neverRoundsDownToZero(int capacity) {
        // The bug this pins: Math.round(4 * 0.10) is 0. A class with no Tatkal
        // inventory at all is a silent hole, not a rounding difference - every
        // Tatkal request against it returns SEAT_UNAVAILABLE, which FR-51
        // excludes from the error budget, so it would not even show up as a
        // failure.
        assertTrue(
                SeedGenerator.tatkalPoolSize(capacity) >= 1,
                "capacity " + capacity + " produced a TATKAL pool of zero berths");
    }

    @Test
    @DisplayName("an empty class has no TATKAL pool, rather than a phantom berth")
    void zeroCapacityYieldsZero() {
        assertEquals(0, SeedGenerator.tatkalPoolSize(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 24, 48, 64, 72, 144, 576, 5000})
    @DisplayName("TATKAL never exceeds capacity, so GENERAL is never negative")
    void neverExceedsCapacity(int capacity) {
        int tatkal = SeedGenerator.tatkalPoolSize(capacity);
        assertTrue(
                tatkal <= capacity,
                "TATKAL pool of " + tatkal + " exceeds capacity " + capacity
                        + "; the GENERAL split would be negative");
    }

    @Test
    @DisplayName("it is 10 percent, not 20 - the Phase 0 regression, pinned")
    void isTenPercentNotTwenty() {
        // Stated as its own case so a future edit back to 0.20 fails with a
        // message that names the requirement rather than an arithmetic mismatch.
        assertEquals(
                8,
                SeedGenerator.tatkalPoolSize(72),
                "FR-9 mandates ceil(0.10 x capacity). A sleeper coach of 72 berths yields 8"
                        + " TATKAL berths, not 14. See DD-030.");
    }
}
