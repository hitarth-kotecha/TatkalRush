package io.tatkalrush.ops.seed;

/**
 * Parameters for one seeding run (FR-48, FR-50, FR-69).
 *
 * @param seed the PRNG seed. Two runs with the same seed must produce
 *     byte-identical data, or the Strategy A vs Strategy B comparison in §9.4 is
 *     not a controlled comparison.
 * @param trainCount FR-48: 20 trains.
 * @param userCount FR-69: at least 5,000 synthetic users. Not a cosmetic number —
 *     see {@link SeedGenerator} and §19.5.
 */
public record SeedConfig(long seed, int trainCount, int userCount) {

    /**
     * The committed default. Changing this invalidates every benchmark in
     * {@code docs/benchmarks/} for comparison purposes, so it is a decision that
     * belongs in the design log, not a knob.
     */
    public static final long DEFAULT_SEED = 20261001L;

    public static SeedConfig defaults() {
        return new SeedConfig(DEFAULT_SEED, 20, 5_000);
    }

    public SeedConfig {
        if (trainCount <= 0) {
            throw new IllegalArgumentException("trainCount must be positive");
        }
        if (userCount < 5_000) {
            // FR-69 is a floor, not a suggestion. Below it, FR-60's 10 rps
            // per-user cap makes the load harness rate-limit itself, and §19.5
            // voids any run with a non-zero RATE_LIMITED count. Failing here is
            // far better than discovering it in a benchmark report.
            throw new IllegalArgumentException(
                    "FR-69 requires at least 5,000 synthetic users, got " + userCount);
        }
    }
}
