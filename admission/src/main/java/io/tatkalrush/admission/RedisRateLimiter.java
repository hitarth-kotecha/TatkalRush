package io.tatkalrush.admission;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import io.tatkalrush.application.ports.RateLimiter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-60 on Redis (§10.5's {@code rate:{userId}:{bucket}}).
 *
 * <p>The window arithmetic is in {@code rate-limit.lua}, which explains why it is
 * two buckets and why it has to be one script. This class computes the bucket
 * names and the elapsed fraction, and decides what to do when Redis is not there.
 */
public final class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** §10.5: 2 s, so a bucket outlives its own second and the one that follows. */
    private static final int BUCKET_TTL_SECONDS = 2;

    private final RedisCommands<String, String> redis;
    private final String script;
    private final int limitPerSecond;
    private volatile String digest;

    public RedisRateLimiter(RedisCommands<String, String> redis, int limitPerSecond) {
        this.redis = redis;
        this.limitPerSecond = limitPerSecond;
        this.script = read();
    }

    @Override
    public Decision check(long userId, Instant now) {
        long currentBucket = now.getEpochSecond();
        double elapsed = now.getNano() / 1_000_000_000.0;

        try {
            List<Long> reply =
                    evaluate(
                            new String[] {
                                key(userId, currentBucket), key(userId, currentBucket - 1)
                            },
                            String.valueOf(limitPerSecond),
                            String.valueOf(elapsed),
                            String.valueOf(BUCKET_TTL_SECONDS));

            if (reply.get(0) == 1L) {
                return new Decision.Allowed(reply.get(1).intValue());
            }

            // Until this bucket ends. The window slides, so room appears
            // continuously rather than at a boundary - but a client that waits
            // this long is certain to find some.
            long millisToNextBucket = 1_000 - (now.toEpochMilli() % 1_000);
            return new Decision.Limited(Duration.ofMillis(millisToNextBucket));

        } catch (RuntimeException e) {
            // FAIL OPEN. See RateLimiter's contract: chaos C2 flushes Redis during
            // P2, and a limiter that rejected everything for the duration would
            // turn C2 into a measurement of this class - and void every C2 run,
            // since §19.5 says a single RATE_LIMITED voids one.
            //
            // WARN rather than ERROR: the run is still valid and the system is
            // still correct. What has been lost is a protection, not a guarantee.
            log.warn("rate limiter unavailable, allowing request for user {}: {}", userId, e.toString());
            return new Decision.Allowed(limitPerSecond);
        }
    }

    /**
     * Braces around the user id are deliberate, and not decoration.
     *
     * <p>In Redis Cluster {@code {...}} is the hash-tag syntax: the slot is
     * computed from the braced substring alone. Both of a user's buckets therefore
     * land on the same node, which a two-key script requires. Without the braces
     * this works perfectly on a single node and fails the day anyone shards it —
     * the worst kind of latent bug, because nothing in a single-node test can see
     * it.
     */
    private static String key(long userId, long bucket) {
        return "rate:{" + userId + "}:" + bucket;
    }

    @SuppressWarnings("unchecked")
    private List<Long> evaluate(String[] keys, String... args) {
        String cached = digest;
        if (cached == null) {
            cached = redis.scriptLoad(script);
            digest = cached;
        }
        try {
            return (List<Long>) redis.evalsha(cached, ScriptOutputType.MULTI, keys, args);
        } catch (RuntimeException e) {
            if (!isNoScript(e)) {
                throw e;
            }
            // The script cache is NOT durable - a restart or SCRIPT FLUSH empties
            // it - so every EVALSHA needs this fallback. Without it the limiter
            // fails open forever after the first Redis restart, silently.
            digest = redis.scriptLoad(script);
            return (List<Long>) redis.evalsha(digest, ScriptOutputType.MULTI, keys, args);
        }
    }

    private static boolean isNoScript(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("NOSCRIPT")) {
                return true;
            }
        }
        return false;
    }

    private static String read() {
        try (InputStream in = RedisRateLimiter.class.getResourceAsStream("/lua/rate-limit.lua")) {
            if (in == null) {
                throw new IllegalStateException("/lua/rate-limit.lua is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read rate-limit.lua", e);
        }
    }
}
