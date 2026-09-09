package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * FR-15's two-second availability cache.
 *
 * <h2>What is cached is a number, not a response</h2>
 *
 * <p>FR-15 keys this {@code (train, date, from, to, class, pool)} — which is
 * exactly {@link PoolKey} plus {@link SegmentRange}, and conspicuously <em>not</em>
 * the train list. Caching whole search responses would be fewer round trips and
 * would weld together two things with entirely different volatility: the set of
 * trains serving a route does not change during a benchmark run, while
 * availability changes thousands of times a second. One key would expire the
 * stable half at the volatile half's rate, and two searches sharing a train would
 * share nothing.
 *
 * <p>Per-pool keys mean the hot train everybody is searching is <b>one</b> entry,
 * hit by every query that touches it whatever station pair was asked about. That
 * is where FR-15's "orders of magnitude" actually comes from.
 *
 * <h2>Staleness is bounded; drift is not</h2>
 *
 * <p>A 2 s TTL means an answer is at most 2 s old. That is the bounded imprecision
 * FR-14 licenses. It is <em>not</em> a licence for the underlying free counts to
 * wander from the masks they summarise, which has no bound at all — INV-12 exists
 * for that, and a cache cannot be blamed for it.
 *
 * <h2>Failure is not an error</h2>
 *
 * <p>An unreachable cache must degrade to a slower search, never to a failed one:
 * every miss has a correct answer available one Lua call away. Implementations
 * therefore swallow their own failures and report a miss. This is the opposite
 * stance from {@link RateLimiter}, and deliberately so — failing open on a cache
 * costs latency, whereas failing open on a limiter removes a control.
 */
public interface AvailabilityCache {

    /** What FR-15 keys on, minus the parts {@link PoolKey} already carries. */
    record Key(PoolKey pool, SegmentRange range) {

        public Key {
            if (pool == null || range == null) {
                throw new IllegalArgumentException("pool and range are required");
            }
        }
    }

    /**
     * Cached free-berth counts, in one round trip.
     *
     * <p>Batched rather than one lookup per pool because a single search over a
     * busy route resolves to a dozen or more pools, and a dozen sequential round
     * trips is the whole latency budget NFR-5 allows.
     *
     * @return only the keys that hit. A missing entry means "not cached" — never
     *     zero, which is a legitimate availability answer and must not be
     *     manufactured by a cache miss.
     */
    Map<Key, Integer> getAll(List<Key> keys);

    /** Writes with FR-15's TTL. Failures are swallowed: a lost write is a miss. */
    void putAll(Map<Key, Integer> freeBerths);

    /** Single-key read, for tests and for callers holding exactly one key. */
    default OptionalInt get(Key key) {
        Integer value = getAll(List.of(key)).get(key);
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }
}
