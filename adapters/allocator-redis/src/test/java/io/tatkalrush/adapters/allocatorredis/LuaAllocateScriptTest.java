package io.tatkalrush.adapters.allocatorredis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code allocate.lua} against a real Redis (§9.2, FR-3a).
 *
 * <p><b>Why a container and not a fake.</b> Everything that makes Strategy A
 * correct is a property of the runtime rather than of the code: Redis executes
 * Lua single-threaded, and Lua 5.1 has no 64-bit integers so every mask operation
 * goes through the {@code bit} library on 32-bit halves. An in-memory stand-in
 * would verify neither, and the second is precisely where FR-3a says bugs hide.
 *
 * <p>Two facts about the runtime, established by experiment before the script was
 * written and relied on throughout it:
 *
 * <pre>
 *   struct.unpack('&lt;I4')  returns UNSIGNED   ->  2147483648
 *   bit.band(hi, hi)      returns SIGNED     -> -2147483648
 * </pre>
 *
 * <p>Same 32 bits, different numbers. Anything stored back is normalised, and the
 * segment-63 tests below are what prove it.
 */
class LuaAllocateScriptTest {

    // Same image compose.yaml pins. A different Redis would be a different Lua.
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final String ALLOCATE = load("/lua/allocate.lua");
    private static final String INIT_POOL = load("/lua/init-pool.lua");

    private static final long TTL = 120_000;
    private static final long T0 = 1_000;

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> redis;

    private static final String[] KEYS = {"m", "h", "f", "d"};

    @BeforeAll
    static void start() {
        REDIS.start();
        client =
                RedisClient.create(
                        "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
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
    }

    private static String load(String resource) {
        try (var in = LuaAllocateScriptTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing script: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void provision(int berths, int segments) {
        redis.eval(
                INIT_POOL,
                ScriptOutputType.INTEGER,
                KEYS,
                String.valueOf(berths),
                String.valueOf(segments));
    }

    /** Runs allocate.lua, returning the raw reply: {@code [status, ...]}. */
    private List<Object> allocate(
            long maskLo, long maskHi, int passengers, String holdId, long nowMs, int segments) {
        return redis.eval(
                ALLOCATE,
                ScriptOutputType.MULTI,
                KEYS,
                String.valueOf(maskLo),
                String.valueOf(maskHi),
                String.valueOf(passengers),
                holdId,
                String.valueOf(TTL),
                String.valueOf(nowMs),
                String.valueOf(segments));
    }

    private static String status(List<Object> reply) {
        // Lettuce's String codec decodes bulk replies to String, not byte[].
        // Assuming bytes here cost a run: every MULTI-reply test failed with a
        // ClassCastException while the exception-expecting ones passed, because
        // those never reach the parser.
        return String.valueOf(reply.get(0));
    }

    private static List<Long> ordinals(List<Object> reply) {
        return reply.subList(1, reply.size()).stream().map(o -> (Long) o).toList();
    }

    /** Free berths on a segment, read straight out of the packed blob. */
    private long freeOn(int segment) {
        return redis.eval(
                "local f = redis.call('GET', KEYS[1])\n"
                        + "return struct.unpack('<I4', f, tonumber(ARGV[1]) * 4 + 1)",
                ScriptOutputType.INTEGER,
                new String[] {"f"},
                String.valueOf(segment));
    }

    // ------------------------------------------------------------ Appendix B

    @Nested
    @DisplayName("Appendix B, executed inside Redis")
    class AppendixB {

        @Test
        @DisplayName("one berth serves three journeys on disjoint legs")
        void oneBerthThreeJourneys() {
            // The same table as the SDD's Appendix B and the README, now proven
            // in the Lua implementation rather than only in the Java one.
            // Route NDLS(0)-KOTA(1)-RTM(2)-ST(3)-BCT(4), 4 segments, ONE berth.
            provision(1, 4);

            assertEquals("OK", status(allocate(0b0011, 0, 1, "b1", T0, 4)), "B1 NDLS->RTM");
            assertEquals("OK", status(allocate(0b1000, 0, 1, "b2", T0, 4)), "B2 ST->BCT");
            assertEquals(
                    "UNAVAILABLE",
                    status(allocate(0b0110, 0, 1, "b3", T0, 4)),
                    "B3 KOTA->ST overlaps B1 on segment 1");
            assertEquals(
                    "OK",
                    status(allocate(0b0100, 0, 1, "b4", T0, 4)),
                    "B4 RTM->ST fits the gap between B1 and B2");

            for (int seg = 0; seg < 4; seg++) {
                assertEquals(0, freeOn(seg), "segment " + seg + " should be fully sold");
            }
        }

        @Test
        @DisplayName("FR-5: the lowest ordinal that FITS, not the lowest that is empty")
        void picksLowestFittingOrdinal() {
            provision(3, 4);

            assertEquals(List.of(0L), ordinals(allocate(0b0011, 0, 1, "a", T0, 4)));
            // Overlaps berth 0, so it moves on.
            assertEquals(List.of(1L), ordinals(allocate(0b0110, 0, 1, "b", T0, 4)));
            // Does NOT overlap berth 0, so it comes back to it.
            assertEquals(List.of(0L), ordinals(allocate(0b1100, 0, 1, "c", T0, 4)));
        }
    }

    // ---------------------------------------------------------------- FR-3a

    @Nested
    @DisplayName("FR-3a: the 64-segment boundary in Lua 5.1")
    class Boundary {

        @Test
        @DisplayName("segment 63 is allocatable and blocks a second attempt")
        void segment63() {
            // hi = 0x80000000. struct.unpack hands this back as 2147483648 while
            // bit.band returns -2147483648, and the script must treat them as the
            // same 32 bits. If it does not, this either allocates twice or never.
            provision(1, 64);
            long hiBit = 2147483648L;

            assertEquals("OK", status(allocate(0, hiBit, 1, "s63", T0, 64)));
            assertEquals("UNAVAILABLE", status(allocate(0, hiBit, 1, "s63-again", T0, 64)));
            assertEquals(0, freeOn(63));
            assertEquals(1, freeOn(0), "segment 0 is untouched by a [63,64) booking");
        }

        @Test
        @DisplayName("[0,63) and [63,64) share a berth; half-openness holds at the top")
        void topOfRangeIsHalfOpen() {
            provision(1, 64);

            // [0,63): all 32 low bits, plus bits 32..62 of the high word.
            assertEquals("OK", status(allocate(4294967295L, 2147483647L, 1, "most", T0, 64)));
            // [63,64): only the sign bit. Disjoint, so the same berth takes it.
            assertEquals("OK", status(allocate(0, 2147483648L, 1, "last", T0, 64)));

            for (int seg : new int[] {0, 31, 32, 62, 63}) {
                assertEquals(0, freeOn(seg), "segment " + seg);
            }
        }

        @Test
        @DisplayName("the 32-bit halfway boundary behaves")
        void halfwayBoundary() {
            // Where the two-word split lives, and so where an off-by-one in the
            // lo/hi handling would surface.
            provision(1, 64);

            assertEquals("OK", status(allocate(2147483648L, 0, 1, "seg31", T0, 64)));
            assertEquals(
                    "OK", status(allocate(0, 1, 1, "seg32", T0, 64)), "segment 32 is a new word");
            assertEquals("UNAVAILABLE", status(allocate(2147483648L, 0, 1, "seg31b", T0, 64)));
        }
    }

    // ---------------------------------------------------------- §9.2 reaping

    @Nested
    @DisplayName("§9.2: lazy reaping")
    class Reaping {

        @Test
        @DisplayName("an expired hold is reclaimed by the next allocation")
        void expiredHoldReclaimed() {
            // Correctness must not depend on the background reaper running. A
            // stalled reaper would otherwise lose seats permanently.
            provision(1, 4);

            assertEquals("OK", status(allocate(0b1111, 0, 1, "first", T0, 4)));
            assertEquals("UNAVAILABLE", status(allocate(0b1111, 0, 1, "second", T0, 4)));

            assertEquals(
                    "OK",
                    status(allocate(0b1111, 0, 1, "third", T0 + TTL + 1, 4)),
                    "past the TTL the script must reap before scanning");

            assertEquals(1, redis.zcard("h"), "only the new hold survives");
            assertEquals(0, freeOn(0), "the new hold occupies the berth");
        }

        @Test
        @DisplayName("reaping restores free counts, not just masks")
        void reapingRestoresFreeCounts() {
            // The failure mode DD-012 describes: masks come back and counts do
            // not, so search reports zero availability while every mask-based
            // invariant passes.
            provision(2, 4);
            allocate(0b0011, 0, 2, "h", T0, 4);
            assertEquals(0, freeOn(0));

            // A non-overlapping request forces a reap without consuming segment 0.
            allocate(0b1100, 0, 1, "later", T0 + TTL + 1, 4);

            assertEquals(2, freeOn(0), "segment 0 must be fully free again");
            assertEquals(1, freeOn(2), "the new hold took one berth on segment 2");
        }
    }

    // ------------------------------------------------------------ validation

    @Nested
    @DisplayName("misuse is refused with a message that names the problem")
    class Validation {

        @Test
        @DisplayName("a segment count disagreeing with the pool is named, not crashed on")
        void segmentCountMismatch() {
            // Before this check the script read past the end of the free-count
            // blob and failed with "bad argument #2 to 'unpack' (data string too
            // short)", which says nothing about which side is wrong. Found by
            // hand-testing; a route changing length reproduces it exactly.
            provision(1, 4);

            var error =
                    assertThrows(Exception.class, () -> allocate(0b1111, 0, 1, "h", T0, 64));
            assertTrue(
                    error.getMessage().contains("segment count mismatch"),
                    () -> "expected a named mismatch, got: " + error.getMessage());
        }

        @Test
        @DisplayName("an unprovisioned pool is refused")
        void unprovisionedPool() {
            var error = assertThrows(Exception.class, () -> allocate(0b1111, 0, 1, "h", T0, 4));
            assertTrue(
                    error.getMessage().contains("not initialised"),
                    () -> "expected a clear message, got: " + error.getMessage());
        }
    }
}
