package io.tatkalrush.adapters.allocatorredis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.tatkalrush.application.ports.AvailabilityCache;
import io.tatkalrush.application.ports.AvailabilityCache.Key;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * FR-15's cache, against real Redis.
 *
 * <p>Two of these carry the design. {@code aMissIsAbsentNotZero} is the contract
 * the whole search path depends on — a cache that answers 0 for "I do not know"
 * reports a full train as sold out, and nothing above it could tell the difference.
 * {@code entriesExpire} is the other: without a TTL this is not a two-second
 * approximation, it is an answer that never expires, which is the unbounded drift
 * FR-14 does not license.
 */
class RedisAvailabilityCacheTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final PoolKey GENERAL =
            new PoolKey(42L, TravelClass.SL, QuotaType.GENERAL);
    private static final PoolKey TATKAL = new PoolKey(42L, TravelClass.SL, QuotaType.TATKAL);
    private static final SegmentRange SHORT = new SegmentRange(0, 2);
    private static final SegmentRange LONG = new SegmentRange(0, 4);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> redis;

    private AvailabilityCache cache;

    @BeforeAll
    static void start() {
        REDIS.start();
        client = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect();
        redis = connection.sync();
    }

    @AfterAll
    static void stop() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        REDIS.stop();
    }

    @BeforeEach
    void reset() {
        redis.flushall();
        cache = new RedisAvailabilityCache(redis, Duration.ofSeconds(2));
    }

    // ── the contract the search path depends on ─────────────────────────────

    @Nested
    @DisplayName("a miss is an absence, never a number")
    class Misses {

        @Test
        void aMissIsAbsentNotZero() {
            Map<Key, Integer> hits = cache.getAll(List.of(key(GENERAL, LONG)));

            assertTrue(hits.isEmpty());
            assertFalse(
                    hits.containsKey(key(GENERAL, LONG)),
                    "no entry at all - 0 is a legitimate answer meaning sold out, and "
                            + "manufacturing one from a missing key advertises a full train "
                            + "as full up");
        }

        @Test
        void aPartialBatchReturnsOnlyTheKeysThatHit() {
            cache.putAll(Map.of(key(GENERAL, LONG), 31));

            var hits =
                    cache.getAll(
                            List.of(key(GENERAL, LONG), key(TATKAL, LONG), key(GENERAL, SHORT)));

            assertEquals(Map.of(key(GENERAL, LONG), 31), hits);
        }

        @Test
        void aZeroSurvivesTheRoundTrip() {
            // The other half of the rule. Zero is a real answer and must come back
            // as one - a cache that treated it as "nothing stored" would recompute
            // a sold-out pool on every single search during precisely the spike
            // FR-15 exists to survive.
            cache.putAll(Map.of(key(GENERAL, LONG), 0));

            assertEquals(Map.of(key(GENERAL, LONG), 0), cache.getAll(List.of(key(GENERAL, LONG))));
        }

        @Test
        void anEmptyRequestIsNotACommand() {
            // MGET with no keys is a syntax error, so the guard is load bearing.
            assertTrue(cache.getAll(List.of()).isEmpty());
        }
    }

    // ── FR-15's TTL ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FR-15: two seconds, and no longer")
    class Expiry {

        @Test
        void entriesCarryTheConfiguredTtl() {
            cache.putAll(Map.of(key(GENERAL, LONG), 31));

            long ttl = redis.pttl(RedisAvailabilityCache.keyOf(key(GENERAL, LONG)));

            assertTrue(ttl > 0, "an entry with no TTL never expires, which is not a cache");
            assertTrue(ttl <= 2000, "at most the configured 2 s, got " + ttl + " ms");
        }

        @Test
        void entriesExpire() throws InterruptedException {
            var shortLived = new RedisAvailabilityCache(redis, Duration.ofMillis(120));
            shortLived.putAll(Map.of(key(GENERAL, LONG), 31));
            assertFalse(shortLived.getAll(List.of(key(GENERAL, LONG))).isEmpty());

            Thread.sleep(250);

            assertTrue(
                    shortLived.getAll(List.of(key(GENERAL, LONG))).isEmpty(),
                    "an expired entry is a miss, and a miss recomputes");
        }

        @Test
        void aZeroOrNegativeTtlIsRejectedAtConstruction() {
            // PSETEX rejects a non-positive TTL at call time. Caught here so a
            // misconfiguration fails at startup rather than on the first search of
            // a load run.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RedisAvailabilityCache(redis, Duration.ZERO));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RedisAvailabilityCache(redis, Duration.ofSeconds(-1)));
        }
    }

    // ── FR-15's key ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the key distinguishes what FR-15 says it must")
    class Keying {

        @Test
        void generalAndTatkalDoNotShareAnEntry() {
            cache.putAll(Map.of(key(GENERAL, LONG), 31, key(TATKAL, LONG), 4));

            var hits = cache.getAll(List.of(key(GENERAL, LONG), key(TATKAL, LONG)));

            // FR-10 makes these different by definition. FR-15's key needed `pool`
            // added for exactly this: without it one pool's answer is served for
            // the other's question for two seconds.
            assertEquals(31, hits.get(key(GENERAL, LONG)));
            assertEquals(4, hits.get(key(TATKAL, LONG)));
        }

        @Test
        void twoRangesOnOnePoolDoNotShareAnEntry() {
            cache.putAll(Map.of(key(GENERAL, SHORT), 40, key(GENERAL, LONG), 31));

            var hits = cache.getAll(List.of(key(GENERAL, SHORT), key(GENERAL, LONG)));

            // A longer journey can never have more berths free than a shorter one
            // inside it, so sharing a key would over-report the long one.
            assertEquals(40, hits.get(key(GENERAL, SHORT)));
            assertEquals(31, hits.get(key(GENERAL, LONG)));
        }

        @Test
        void thePoolIsInsideTheHashTagAndTheRangeIsNot() {
            assertEquals("search:{42:SL:TATKAL}:0-4", RedisAvailabilityCache.keyOf(key(TATKAL, LONG)));
            assertEquals("search:{42:SL:GENERAL}:0-2", RedisAvailabilityCache.keyOf(key(GENERAL, SHORT)));
        }

        @Test
        void theKeyIsDistinctFromTheAllocatorsOwnKeys() {
            cache.putAll(Map.of(key(GENERAL, LONG), 31));

            // The allocator's freecount: is authoritative; this is a copy of a
            // derived number. An operator running redis-cli --scan has to be able
            // to tell them apart, because only one of them can be trusted.
            assertTrue(
                    redis.keys("search:*").contains(RedisAvailabilityCache.keyOf(key(GENERAL, LONG))));
            assertTrue(redis.keys("freecount:*").isEmpty());
        }
    }

    // ── degradation ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("failure is a miss, not an error")
    class Degradation {

        @Test
        void aValueSomebodyElseWroteIsIgnoredRatherThanThrown() {
            redis.set(RedisAvailabilityCache.keyOf(key(GENERAL, LONG)), "not-a-number");

            assertTrue(
                    cache.getAll(List.of(key(GENERAL, LONG))).isEmpty(),
                    "a key collision must degrade to a recompute, not a 500 on the "
                            + "endpoint carrying nine tenths of the load");
        }

        @Test
        void anUnparseableValueIsOverwrittenByTheNextWrite() {
            redis.set(RedisAvailabilityCache.keyOf(key(GENERAL, LONG)), "garbage");
            cache.putAll(Map.of(key(GENERAL, LONG), 31));

            assertEquals(31, cache.getAll(List.of(key(GENERAL, LONG))).get(key(GENERAL, LONG)));
        }

        @Test
        void anUnreachableRedisReportsMissesRatherThanFailing() {
            var broken = new RedisAvailabilityCache(closedConnection(), Duration.ofSeconds(2));

            assertTrue(broken.getAll(List.of(key(GENERAL, LONG))).isEmpty());
            // And the write must not propagate either: every value here is one Lua
            // call away from being recomputed, so an unreachable cache costs
            // latency and nothing else. This is the opposite of the rate limiter's
            // stance, and deliberately so.
            broken.putAll(Map.of(key(GENERAL, LONG), 31));
        }

        @Test
        void anEmptyWriteIsNotACommand() {
            cache.putAll(Map.of());

            assertTrue(redis.keys("search:*").isEmpty());
        }
    }

    // ── batching ────────────────────────────────────────────────────────────

    @Test
    void aBatchOfManyKeysIsOneCommand() {
        var many = new LinkedHashMap<Key, Integer>();
        for (int schedule = 1; schedule <= 25; schedule++) {
            many.put(
                    key(new PoolKey(schedule, TravelClass.SL, QuotaType.GENERAL), LONG),
                    schedule);
        }
        cache.putAll(many);

        // MGET spans hash slots, which is correct on the single node §8.3
        // specifies and would need a per-slot fan-out under Redis Cluster. The
        // hash tags make that migration mechanical; this asserts the single-node
        // behaviour the system actually runs on.
        var hits = cache.getAll(List.copyOf(many.keySet()));

        assertEquals(25, hits.size());
        assertEquals(many, hits);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Key key(PoolKey pool, SegmentRange range) {
        return new Key(pool, range);
    }

    /** A connection that is definitely gone, for the degradation tests. */
    private static RedisCommands<String, String> closedConnection() {
        var doomed = client.connect();
        RedisCommands<String, String> commands = doomed.sync();
        doomed.close();
        return commands;
    }
}
