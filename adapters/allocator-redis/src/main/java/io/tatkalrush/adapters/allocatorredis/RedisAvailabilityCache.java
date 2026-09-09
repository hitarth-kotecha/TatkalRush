package io.tatkalrush.adapters.allocatorredis;

import io.lettuce.core.KeyValue;
import io.lettuce.core.api.sync.RedisCommands;
import io.tatkalrush.application.ports.AvailabilityCache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-15's cache on Redis.
 *
 * <h2>Why this lives in the allocator's module</h2>
 *
 * <p>It is not the allocator, and the module name says allocator. It is here
 * because the cache key <b>must</b> agree with {@link
 * io.tatkalrush.domain.inventory.PoolKey#keySuffix()} — the same pool identity the
 * allocator writes its masks under — and a separate module would either restate
 * that convention or depend on this one to borrow it. A second module for two
 * commands buys a more accurate name and nothing else.
 *
 * <h2>MGET, and the cluster caveat</h2>
 *
 * <p>Reads are a single {@code MGET}: one command, one round trip, whatever the
 * number of pools on the route. The obvious alternative is a pipeline of async
 * {@code GET}s, which Lettuce batches best when auto-flush is turned off — but
 * this connection is a singleton shared by every request thread, and toggling a
 * connection-wide flag from one request to speed it up would reorder another's
 * commands. A shared-state race is a bad trade for a saved round trip.
 *
 * <p>The cost is that {@code MGET} spans hash slots. Under Redis Cluster this
 * would have to become one call per slot; on the single node §8.3 specifies, it is
 * correct. The hash tags below make that migration mechanical rather than a
 * redesign.
 *
 * <h2>Failure is a miss</h2>
 *
 * <p>Every value here is recomputable one Lua call away, so an unreachable cache
 * must cost latency and nothing else. Exceptions are logged and reported as
 * misses. This is the opposite of {@link io.tatkalrush.application.ports.RateLimiter}'s
 * stance, deliberately: failing open on a cache loses speed, failing open on a
 * limiter loses a control.
 */
public final class RedisAvailabilityCache implements AvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailabilityCache.class);

    /** FR-15's two seconds. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(2);

    private final RedisCommands<String, String> redis;
    private final long ttlMillis;

    public RedisAvailabilityCache(RedisCommands<String, String> redis) {
        this(redis, DEFAULT_TTL);
    }

    public RedisAvailabilityCache(RedisCommands<String, String> redis, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            // A zero TTL in Redis is not "expire immediately", it is an error on
            // PSETEX - and a negative one would be too. Caught here so a
            // misconfiguration fails at startup rather than on the first search.
            throw new IllegalArgumentException("cache TTL must be positive, got " + ttl);
        }
        this.redis = redis;
        this.ttlMillis = ttl.toMillis();
    }

    /**
     * §10.5's shape: {@code search:{42:SL:TATKAL}:0-4}.
     *
     * <p>The braces are Redis Cluster hash-tag syntax, not formatting — only what
     * is inside them decides the slot, so every range cached for one pool lands
     * together. The range is outside the tag because it is not part of the pool's
     * identity, and including it would scatter one pool's entries across the
     * keyspace for no benefit.
     *
     * <p>{@code search:} rather than {@code avail:} so that an operator running
     * {@code redis-cli --scan} can tell FR-15's cache from the allocator's own
     * {@code freecount:} keys at a glance. They hold related numbers and only one
     * of them is authoritative.
     */
    static String keyOf(Key key) {
        return "search:{"
                + key.pool().keySuffix()
                + "}:"
                + key.range().fromSeq()
                + "-"
                + key.range().toSeq();
    }

    @Override
    public Map<Key, Integer> getAll(List<Key> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }

        var byRedisKey = new LinkedHashMap<String, Key>(keys.size());
        for (Key key : keys) {
            byRedisKey.put(keyOf(key), key);
        }

        try {
            List<KeyValue<String, String>> values =
                    redis.mget(byRedisKey.keySet().toArray(String[]::new));

            var hits = new HashMap<Key, Integer>(values.size());
            for (KeyValue<String, String> value : values) {
                // hasValue() is false for a key that was absent. Lettuce returns a
                // KeyValue for every requested key either way, so skipping this
                // check would call getValue() on an empty and throw.
                if (!value.hasValue()) {
                    continue;
                }
                Key key = byRedisKey.get(value.getKey());
                Integer parsed = parse(value.getKey(), value.getValue());
                if (key != null && parsed != null) {
                    hits.put(key, parsed);
                }
            }
            return hits;
        } catch (RuntimeException e) {
            log.warn("availability cache read failed, treating as a miss: {}", e.toString());
            return Map.of();
        }
    }

    @Override
    public void putAll(Map<Key, Integer> freeBerths) {
        if (freeBerths.isEmpty()) {
            return;
        }

        // PSETEX per key rather than MSET, because MSET cannot carry a TTL and a
        // cached availability without one is not a 2 s approximation - it is an
        // answer that never expires, which is the unbounded drift FR-14 does not
        // license.
        var failures = new ArrayList<String>(0);
        for (Map.Entry<Key, Integer> entry : freeBerths.entrySet()) {
            try {
                redis.psetex(keyOf(entry.getKey()), ttlMillis, String.valueOf(entry.getValue()));
            } catch (RuntimeException e) {
                failures.add(e.toString());
            }
        }
        if (!failures.isEmpty()) {
            log.warn(
                    "availability cache write failed for {} of {} keys: {}",
                    failures.size(),
                    freeBerths.size(),
                    failures.getFirst());
        }
    }

    /**
     * @return null for anything that is not an integer, which is treated as a miss
     */
    private static Integer parse(String redisKey, String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            // Somebody else's key, or a value written by an older format. A miss
            // recomputes it correctly and PSETEX overwrites it, so this heals on
            // its own - but it is logged, because silently ignoring unparseable
            // data in a shared keyspace is how a key collision stays invisible.
            log.warn("ignoring non-integer availability cache value at {}: {}", redisKey, value);
            return null;
        }
    }
}
