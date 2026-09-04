package io.tatkalrush.domain;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Seeded-loop property harness. Carries T-4 and T-7 (SDD §18.1).
 *
 * <p><b>Why this exists instead of jqwik.</b> §18 originally specified jqwik for
 * property-based and differential testing. During the Phase 0 toolchain spike,
 * {@code jqwik-engine:1.10.1} was found to contain string constants printed at
 * engine start instructing AI agents to disregard their instructions and ignore
 * jqwik's own test results, followed by an ANSI erase-line escape that hides the
 * text from a human watching the console. 1.10.0 and earlier are clean; the payload
 * appears in 1.10.1 only. The dependency was dropped (DD-020, DD-022).
 *
 * <p>Two properties had to survive that removal:
 *
 * <ol>
 *   <li><b>Reproducibility.</b> Every run is driven by an explicit seed, printed on
 *       failure, so any failing case replays exactly.
 *   <li><b>Shrinking.</b> The real loss, and the reason this class is not just a
 *       {@code for} loop. A raw T-7 failure can be hundreds of operations long,
 *       while the minimal one tells you which Lua branch diverged. {@link #check}
 *       takes a shrink function and walks greedily to the smallest still-failing
 *       candidate.
 * </ol>
 *
 * <p>Deliberately small. This is not a property-testing framework; it is the part of
 * one this project actually uses.
 */
public final class PropertyRunner {

    private PropertyRunner() {}

    /** Runs {@code assertion} over {@code tries} generated values, without shrinking. */
    public static <T> void check(
            String name, long seed, int tries, Function<Random, T> gen, Consumer<T> assertion) {
        check(name, seed, tries, gen, assertion, t -> List.of());
    }

    /**
     * Runs {@code assertion} over {@code tries} generated values. On failure,
     * repeatedly replaces the failing value with the first candidate from
     * {@code shrink} that still fails, until no candidate does, and reports both the
     * original and the minimal case.
     */
    public static <T> void check(
            String name,
            long seed,
            int tries,
            Function<Random, T> gen,
            Consumer<T> assertion,
            Function<T, List<T>> shrink) {

        Random random = new Random(seed);

        for (int i = 0; i < tries; i++) {
            T value = gen.apply(random);
            Throwable failure = runCatching(assertion, value);
            if (failure == null) {
                continue;
            }

            T minimal = shrinkToMinimal(assertion, value, shrink);
            throw new AssertionError(
                    "Property '%s' failed on try %d/%d (seed=%d)%n  original: %s%n  minimal:  %s"
                            .formatted(name, i + 1, tries, seed, value, minimal),
                    failure);
        }
    }

    private static <T> T shrinkToMinimal(
            Consumer<T> assertion, T failing, Function<T, List<T>> shrink) {
        T current = failing;
        boolean progressed = true;

        while (progressed) {
            progressed = false;
            for (T candidate : shrink.apply(current)) {
                if (runCatching(assertion, candidate) != null) {
                    current = candidate;
                    progressed = true;
                    break;
                }
            }
        }
        return current;
    }

    private static <T> Throwable runCatching(Consumer<T> assertion, T value) {
        try {
            assertion.accept(value);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
