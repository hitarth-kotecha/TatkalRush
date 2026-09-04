package io.tatkalrush.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the test harness. {@link PropertyRunner} carries T-4 and T-7, so a silent
 * defect in it would make those gates pass while checking nothing - the exact
 * failure mode the Phase 0 spike found in surefire's include patterns.
 */
class PropertyRunnerProperties {

    private record Range(int from, int to) {
        @Override
        public String toString() {
            return "[" + from + "," + to + ")";
        }
    }

    private static Range generateRange(java.util.Random r) {
        int from = r.nextInt(64);
        return new Range(from, from + 1 + r.nextInt(64 - from));
    }

    /** Shrink toward the smallest range: narrow first, then slide toward zero. */
    private static List<Range> shrinkRange(Range range) {
        var candidates = new ArrayList<Range>();
        if (range.to() - range.from() > 1) {
            candidates.add(new Range(range.from(), range.to() - 1));
        }
        if (range.from() > 0) {
            candidates.add(new Range(range.from() - 1, range.to()));
        }
        return candidates;
    }

    @Test
    void passesWhenThePropertyHolds() {
        PropertyRunner.check(
                "every generated range is non-empty",
                20260904L,
                500,
                PropertyRunnerProperties::generateRange,
                range -> assertTrue(range.to() > range.from()));
    }

    @Test
    void shrinksToTheMinimalCounterexample() {
        var failure =
                assertThrows(
                        AssertionError.class,
                        () ->
                                PropertyRunner.check(
                                        "no range is wider than one segment (deliberately false)",
                                        20260904L,
                                        200,
                                        PropertyRunnerProperties::generateRange,
                                        range -> {
                                            if (range.to() - range.from() > 1) {
                                                throw new AssertionError("too wide: " + range);
                                            }
                                        },
                                        PropertyRunnerProperties::shrinkRange));

        // The minimal counterexample to "nothing is wider than 1" is width 2 at
        // offset 0. Anything else means the shrinker stopped early.
        assertTrue(
                failure.getMessage().contains("minimal:  [0,2)"),
                () -> "shrinker did not reach [0,2):\n" + failure.getMessage());
    }

    @Test
    void reportsTheSeedSoAFailureCanBeReplayed() {
        var failure =
                assertThrows(
                        AssertionError.class,
                        () ->
                                PropertyRunner.check(
                                        "always fails",
                                        4242L,
                                        1,
                                        PropertyRunnerProperties::generateRange,
                                        range -> {
                                            throw new AssertionError("boom");
                                        }));

        assertTrue(failure.getMessage().contains("seed=4242"), failure.getMessage());
    }
}
