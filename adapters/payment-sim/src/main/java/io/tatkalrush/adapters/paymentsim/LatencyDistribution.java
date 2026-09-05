package io.tatkalrush.adapters.paymentsim;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * FR-53's settlement latency: log-normal, median 800 ms, p99 6 s.
 *
 * <h2>Why log-normal and not a fixed delay</h2>
 *
 * <p>The shape is the point. A fixed or uniform delay makes FR-23's "payments
 * older than 60 seconds" threshold either never fire or always fire, and in both
 * cases the reconciliation sweep stops being a thing under test and becomes either
 * dead code or the only code. A long right tail — most payments settling in under
 * a second, a few taking many — is what produces a population where <em>some</em>
 * payments are genuinely still in flight when the sweep runs, which is the
 * condition FR-23 exists for.
 *
 * <p>It is also what real payment latency looks like, and for a reason worth
 * knowing: latency is <b>multiplicative</b> rather than additive. A request passes
 * through a queue, a network, a database, a downstream processor, and each stage
 * multiplies rather than adds to the delay. The log of such a product is a sum of
 * logs, and sums tend to normal — so the value itself tends to log-normal.
 *
 * <h2>Parameters are derived, not hardcoded</h2>
 *
 * <p>FR-56 lets a chaos scenario change the distribution mid-run, so this takes a
 * median and a p99 — the two numbers an operator can reason about — and solves for
 * μ and σ. For a log-normal, the quantile at probability p is
 * {@code exp(μ + σ·z_p)}, so the median (z = 0) gives {@code μ = ln(median)} and
 * the p99 (z = 2.3263) gives {@code σ = (ln(p99) − ln(median)) / 2.3263}.
 *
 * <p>Hardcoding μ and σ instead would mean an operator asking for "p99 of 12 s"
 * had to solve the algebra themselves, and would get no complaint at all if they
 * passed a σ that produced a p99 of 40.
 */
public final class LatencyDistribution {

    /** The standard normal quantile at p = 0.99. */
    private static final double Z_99 = 2.3263478740408408;

    private final double mu;
    private final double sigma;
    private final Duration cap;

    /**
     * @param median the 50th percentile (FR-53 default: 800 ms)
     * @param p99 the 99th percentile (FR-53 default: 6 s)
     * @param cap a hard ceiling on any single draw. A log-normal has no upper
     *     bound, so roughly one draw in a million from the default parameters
     *     lands past a minute — enough to hold a scheduler thread through an
     *     entire benchmark run and be mistaken for a hang.
     */
    public LatencyDistribution(Duration median, Duration p99, Duration cap) {
        if (median.isNegative() || median.isZero()) {
            throw new IllegalArgumentException("median must be positive, got " + median);
        }
        if (p99.compareTo(median) <= 0) {
            // Not pedantry: p99 <= median gives a non-positive sigma, and a
            // negative one silently INVERTS the distribution so the slow tail
            // becomes a fast one. The simulator would then look well-behaved
            // under exactly the configuration meant to stress it.
            throw new IllegalArgumentException(
                    "p99 (%s) must exceed the median (%s)".formatted(p99, median));
        }

        double medianSeconds = median.toNanos() / 1e9;
        double p99Seconds = p99.toNanos() / 1e9;

        this.mu = Math.log(medianSeconds);
        this.sigma = (Math.log(p99Seconds) - mu) / Z_99;
        this.cap = cap;
    }

    /** FR-53's defaults. */
    public static LatencyDistribution defaults() {
        return new LatencyDistribution(
                Duration.ofMillis(800), Duration.ofSeconds(6), Duration.ofSeconds(60));
    }

    public Duration sample(RandomGenerator random) {
        double seconds = Math.exp(mu + sigma * random.nextGaussian());
        Duration drawn = Duration.ofNanos((long) (seconds * 1e9));
        return drawn.compareTo(cap) > 0 ? cap : drawn;
    }

    /** Exposed so a test can assert the solved parameters rather than only the samples. */
    public double mu() {
        return mu;
    }

    public double sigma() {
        return sigma;
    }
}
