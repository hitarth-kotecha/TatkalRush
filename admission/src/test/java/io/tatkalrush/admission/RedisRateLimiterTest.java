package io.tatkalrush.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.tatkalrush.application.ports.RateLimiter;
import io.tatkalrush.application.ports.RateLimiter.Decision;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * FR-60, against real Redis.
 *
 * <p>§17 says this is "tested by a dedicated integration test with a single user
 * and a tight loop — never via a load profile, where it must never bind". So that
 * is what this is, and it is the only place in the project that deliberately
 * provokes a {@code RATE_LIMITED}.
 *
 * <p>The two tests that carry the design are {@code aBurstAcrossTheBoundaryIsStillLimited}
 * — the case a fixed window lets straight through — and
 * {@code anUnreachableRedisAllowsTheRequest}, which is what keeps chaos C2 a
 * measurement of the system rather than of this class.
 */
class RedisRateLimiterTest {

    private static final int LIMIT = 10;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> redis;

    private RateLimiter limiter;
    private long user;

    @BeforeAll
    static void start() {
        REDIS.start();
        client =
                RedisClient.create(
                        RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
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
    void setUp() {
        redis.flushall();
        limiter = new RedisRateLimiter(redis, LIMIT);
        // A fresh user per test, so a leftover bucket cannot make one test's
        // traffic count against another's.
        user = System.nanoTime() % 1_000_000L;
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("the limit itself")
    class Limiting {

        @Test
        void theFirstTenRequestsInASecondAreAllowed() {
            Instant now = Instant.parse("2026-10-01T06:00:00Z");

            for (int i = 0; i < LIMIT; i++) {
                assertInstanceOf(
                        Decision.Allowed.class, limiter.check(user, now), "request " + i);
            }
        }

        @Test
        void theEleventhIsRejected() {
            Instant now = Instant.parse("2026-10-01T06:00:00Z");
            for (int i = 0; i < LIMIT; i++) {
                limiter.check(user, now);
            }

            assertInstanceOf(Decision.Limited.class, limiter.check(user, now));
        }

        /**
         * A rejected request does not count. Otherwise a client that keeps
         * hammering holds its own window open indefinitely — the limiter would
         * punish the retry rather than the traffic, and a caller that backed off
         * properly would be indistinguishable from one that did not.
         */
        @Test
        void aRejectedRequestDoesNotExtendTheWindow() {
            Instant now = Instant.parse("2026-10-01T06:00:00Z");
            for (int i = 0; i < LIMIT; i++) {
                limiter.check(user, now);
            }

            for (int i = 0; i < 50; i++) {
                limiter.check(user, now);
            }

            // Half a second into the next bucket the previous one is weighted at
            // 50%, so the ten ALLOWED requests contribute five and five slots are
            // free. Had the fifty rejections been counted, sixty would contribute
            // thirty and nothing would get through for seconds.
            //
            // Note what this test does NOT do: check at exactly +1.000 s. At that
            // instant elapsed is 0 and the previous bucket counts in FULL, so the
            // window has not moved at all yet. "A second later" is not "a second
            // of the window later".
            Instant halfwayIntoNext = now.plusMillis(1_500);
            int allowed = 0;
            for (int i = 0; i < LIMIT; i++) {
                if (limiter.check(user, halfwayIntoNext) instanceof Decision.Allowed) {
                    allowed++;
                }
            }

            assertEquals(5, allowed, "rejections must leave no trace in the counter");
        }

        @Test
        void oneUsersTrafficDoesNotCountAgainstAnother() {
            Instant now = Instant.parse("2026-10-01T06:00:00Z");
            for (int i = 0; i < LIMIT; i++) {
                limiter.check(user, now);
            }

            assertInstanceOf(Decision.Allowed.class, limiter.check(user + 1, now));
        }

        @Test
        void aRejectionSaysWhenToComeBack() {
            Instant now = Instant.parse("2026-10-01T06:00:00.250Z");
            for (int i = 0; i < LIMIT; i++) {
                limiter.check(user, now);
            }

            var rejected = assertInstanceOf(Decision.Limited.class, limiter.check(user, now));

            assertEquals(
                    750,
                    rejected.retryAfter().toMillis(),
                    "750 ms of this bucket remain; a client told only 'no' retries at once");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("why two buckets and not one")
    class SlidingWindow {

        /**
         * The case a fixed window lets straight through: a full allowance at the
         * very end of one second and another at the very start of the next — twenty
         * requests in two milliseconds. That boundary is where a retry storm lands,
         * because every client's clock ticks together.
         */
        @Test
        void aBurstAcrossTheBoundaryIsStillLimited() {
            Instant endOfSecond = Instant.parse("2026-10-01T06:00:00.999Z");
            for (int i = 0; i < LIMIT; i++) {
                assertInstanceOf(Decision.Allowed.class, limiter.check(user, endOfSecond));
            }

            Instant startOfNext = Instant.parse("2026-10-01T06:00:01.001Z");

            int allowedAfterBoundary = 0;
            for (int i = 0; i < LIMIT; i++) {
                if (limiter.check(user, startOfNext) instanceof Decision.Allowed) {
                    allowedAfterBoundary++;
                }
            }

            // A fixed window allows TEN here - the counter reset at the boundary.
            // The sliding window allows one: the previous bucket is weighted in at
            // 99.9%, so the estimate starts at 9.99 and the second request tips it
            // over. Eleven requests in two milliseconds rather than twenty.
            //
            // Not zero, and that is the documented approximation rather than a
            // defect: the estimate is 9.99, which is genuinely under a limit of 10.
            assertEquals(
                    1,
                    allowedAfterBoundary,
                    "a fixed window would allow ten more here, for twenty in 2 ms");
        }

        @Test
        void theWindowSlidesRatherThanResetting() {
            Instant first = Instant.parse("2026-10-01T06:00:00.000Z");
            for (int i = 0; i < LIMIT; i++) {
                limiter.check(user, first);
            }

            // Half a second in, the previous bucket still counts for 50%: five of
            // the ten are still "in the window", so five slots are free.
            Instant halfway = Instant.parse("2026-10-01T06:00:01.500Z");
            int allowed = 0;
            for (int i = 0; i < LIMIT; i++) {
                if (limiter.check(user, halfway) instanceof Decision.Allowed) {
                    allowed++;
                }
            }

            assertEquals(5, allowed, "half the previous second's traffic still weighs in");
        }

        @Test
        void afterAFullSecondThePreviousBucketIsGone() {
            Instant first = Instant.parse("2026-10-01T06:00:00.000Z");
            for (int i = 0; i < LIMIT; i++) {
                limiter.check(user, first);
            }

            Instant muchLater = first.plusSeconds(5);
            for (int i = 0; i < LIMIT; i++) {
                assertInstanceOf(Decision.Allowed.class, limiter.check(user, muchLater));
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("what it does when Redis is not there")
    class FailingOpen {

        /**
         * Chaos C2 flushes Redis during P2. A limiter that failed closed would
         * reject every request for the duration — making C2 a measurement of this
         * class, and voiding every C2 run under §19.5's rule that a single
         * RATE_LIMITED voids one.
         */
        @Test
        void anUnreachableRedisAllowsTheRequest() {
            var deadClient = RedisClient.create(RedisURI.create("127.0.0.1", 6));
            deadClient.setDefaultTimeout(java.time.Duration.ofMillis(250));

            RateLimiter offline;
            try {
                offline = new RedisRateLimiter(deadClient.connect().sync(), LIMIT);
            } catch (RuntimeException connectFailed) {
                // Cannot even connect, which is the same condition seen earlier.
                // Construct against a live connection and then break it below.
                offline = limiter;
                connection.close();
                assertInstanceOf(
                        Decision.Allowed.class,
                        offline.check(user, Instant.now()),
                        "a limiter that fails closed turns a Redis blip into an outage");
                reopen();
                return;
            }

            assertInstanceOf(Decision.Allowed.class, offline.check(user, Instant.now()));
            deadClient.shutdown();
        }

        /** A FLUSHALL mid-run loses the counters and must not reject anything. */
        @Test
        void aFlushedRedisJustStartsCountingAgain() {
            Instant now = Instant.parse("2026-10-01T06:00:00Z");
            for (int i = 0; i < LIMIT; i++) {
                limiter.check(user, now);
            }
            assertInstanceOf(Decision.Limited.class, limiter.check(user, now));

            redis.flushall();

            assertInstanceOf(
                    Decision.Allowed.class,
                    limiter.check(user, now),
                    "C2 must cost a protection, never a rejection");
        }

        /**
         * The Redis script cache is not durable. Without a NOSCRIPT fallback the
         * limiter fails open forever after the first restart — silently, because
         * failing open looks exactly like working.
         */
        @Test
        void aFlushedScriptCacheIsReloadedRatherThanFailingOpenForever() {
            Instant now = Instant.parse("2026-10-01T06:00:00Z");
            limiter.check(user, now);

            redis.scriptFlush();

            for (int i = 0; i < LIMIT - 1; i++) {
                assertInstanceOf(Decision.Allowed.class, limiter.check(user, now));
            }
            assertInstanceOf(
                    Decision.Limited.class,
                    limiter.check(user, now),
                    "still counting, so the script really was reloaded");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("§10.5's key shape")
    class Keys {

        /**
         * The braces are Redis Cluster hash-tag syntax, not decoration: the slot is
         * computed from the braced part alone, so a user's two buckets always land
         * on one node. A two-key script requires that, and without the braces this
         * works on a single node and fails the day anyone shards it.
         */
        @Test
        void bothBucketsCarryTheUserIdAsAHashTag() {
            limiter.check(user, Instant.parse("2026-10-01T06:00:00Z"));

            var keys = redis.keys("rate:*");

            assertEquals(1, keys.size(), keys.toString());
            assertTrue(keys.getFirst().startsWith("rate:{" + user + "}:"), keys.getFirst());
        }

        /** §10.5 gives these a 2 s TTL, so nothing sweeps them. */
        @Test
        void bucketsExpireThemselves() {
            limiter.check(user, Instant.parse("2026-10-01T06:00:00Z"));

            String key = redis.keys("rate:*").getFirst();

            assertTrue(redis.ttl(key) > 0, "a bucket with no TTL leaks one key per user per second");
            assertTrue(redis.ttl(key) <= 2, "§10.5 says 2 s");
        }
    }

    private static void reopen() {
        connection = client.connect();
        redis = connection.sync();
    }
}
