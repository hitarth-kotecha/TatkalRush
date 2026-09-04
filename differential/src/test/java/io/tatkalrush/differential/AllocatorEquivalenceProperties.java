package io.tatkalrush.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.tatkalrush.adapters.allocatorredis.RedisSeatAllocator;
import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.domain.PropertyRunner;
import io.tatkalrush.domain.inventory.BerthPool;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentMask;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * <b>T-7 — differential allocator equivalence.</b> An AC-1.7 gate.
 *
 * <p>The algorithm is specified once and implemented twice: Java in
 * {@code domain/inventory}, Lua in {@code allocate.lua}. They cannot share code —
 * Strategy A executes inside the Redis process, and the atomicity that makes it
 * correct depends on the algorithm never leaving Redis mid-execution (§9.1,
 * DD-001). So equivalence is a <b>tested property</b>, and this is the test.
 *
 * <p><b>This is what makes §9.4 a controlled comparison.</b> Without it, the
 * benchmark compares two different programs and attributes the difference to
 * their concurrency strategies. With it, the two are known to implement the same
 * algorithm, and the measured difference is the thing the project set out to
 * measure.
 *
 * <p><b>Results are not enough; state is compared too.</b> §9.2 is explicit about
 * why: a Lua bug that returns the right answer via wrong state — decrementing a
 * free count by 1 instead of {@code passengerCount}, say — passes the entire
 * contract suite and then silently corrupts availability for the rest of the run.
 * So after <em>every</em> operation this asserts identical results, identical
 * per-berth masks, and identical per-segment free counts.
 */
class AllocatorEquivalenceProperties {

    private static final long SEED = 20261001L;
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final long TTL = 120_000;

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisSeatAllocator redisAllocator;

    private static final AtomicLong NEXT_SCHEDULE = new AtomicLong(1);

    @BeforeAll
    static void start() {
        REDIS.start();
        client =
                RedisClient.create(
                        "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect();
        redisAllocator = new RedisSeatAllocator(connection.sync());
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

    // ------------------------------------------------------------- scenarios

    private sealed interface Op {
        record Allocate(SegmentRange range, int passengers, long atMillis) implements Op {
            @Override
            public String toString() {
                return "ALLOC %s x%d @%dms".formatted(range, passengers, atMillis);
            }
        }

        record Release(int holdIndex) implements Op {
            @Override
            public String toString() {
                return "FREE hold#" + holdIndex;
            }
        }
    }

    private record Scenario(int berths, int segments, List<Op> ops) {
        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("%d berths, %d segments%n".formatted(berths, segments));
            for (int i = 0; i < ops.size(); i++) {
                sb.append("      %2d. %s%n".formatted(i + 1, ops.get(i)));
            }
            return sb.toString();
        }
    }

    private static Scenario randomScenario(Random r) {
        // Small pools, because contention is the interesting state. A 72-berth
        // pool with a dozen operations never runs out of berths and so never
        // exercises the paths where the two implementations could diverge.
        int berths = 1 + r.nextInt(5);

        // Segment counts spanning the 32-bit split, which is where the Lua
        // implementation's two-word representation would break if it were wrong.
        int segments = r.nextInt(3) == 0 ? 34 + r.nextInt(31) : 2 + r.nextInt(8);

        int opCount = 1 + r.nextInt(12);
        long clock = 0;
        var ops = new ArrayList<Op>(opCount);

        for (int i = 0; i < opCount; i++) {
            // Time occasionally jumps past a TTL so the lazy reap inside BOTH
            // implementations is exercised. A clock that never passes the TTL
            // never reaps, and the reaping paths are the most intricate part of
            // either implementation.
            clock += r.nextInt(4) == 0 ? TTL + 1 : r.nextInt(20_000);

            if (r.nextInt(5) == 0) {
                ops.add(new Op.Release(r.nextInt(6)));
            } else {
                int from = r.nextInt(segments);
                int to = from + 1 + r.nextInt(segments - from);
                ops.add(new Op.Allocate(SegmentRange.of(from, to), 1 + r.nextInt(3), clock));
            }
        }
        return new Scenario(berths, segments, List.copyOf(ops));
    }

    private static List<Scenario> shrink(Scenario s) {
        var candidates = new ArrayList<Scenario>();
        if (s.ops().size() > 1) {
            candidates.add(new Scenario(s.berths(), s.segments(), s.ops().subList(0, s.ops().size() - 1)));
            candidates.add(new Scenario(s.berths(), s.segments(), s.ops().subList(1, s.ops().size())));
        }
        if (s.berths() > 1) {
            candidates.add(new Scenario(s.berths() - 1, s.segments(), s.ops()));
        }
        return candidates;
    }

    // ------------------------------------------------------------- the test

    @Test
    @DisplayName("T-7: Java and Lua agree on results AND state after every operation")
    void javaAndLuaAgreeStepForStep() {
        PropertyRunner.check(
                "domain/inventory == allocate.lua, step for step",
                SEED,
                400,
                AllocatorEquivalenceProperties::randomScenario,
                AllocatorEquivalenceProperties::runBothAndCompare,
                AllocatorEquivalenceProperties::shrink);
    }

    @Test
    @DisplayName("T-7 / FR-3a: they agree across the full 64-segment width")
    void agreeAtTheTopOfTheRange() {
        // Segment 63 is where Lua's two-32-bit-word representation is most likely
        // to diverge from a Java long: struct.unpack returns it unsigned and
        // bit.band returns it signed. Generated deliberately rather than hoped for.
        PropertyRunner.check(
                "the implementations agree on ranges touching segment 63",
                SEED + 7,
                200,
                r -> {
                    int berths = 1 + r.nextInt(3);
                    var ops = new ArrayList<Op>();
                    long clock = 0;
                    for (int i = 0; i < 1 + r.nextInt(6); i++) {
                        clock += r.nextInt(3) == 0 ? TTL + 1 : r.nextInt(10_000);
                        int from = r.nextInt(3) == 0 ? 60 + r.nextInt(4) : r.nextInt(64);
                        int to = from + 1 + r.nextInt(64 - from);
                        ops.add(new Op.Allocate(SegmentRange.of(from, to), 1 + r.nextInt(2), clock));
                    }
                    return new Scenario(berths, 64, List.copyOf(ops));
                },
                AllocatorEquivalenceProperties::runBothAndCompare,
                AllocatorEquivalenceProperties::shrink);
    }

    // -------------------------------------------------------------- harness

    /** Runs one scenario against both implementations, comparing after each step. */
    private static void runBothAndCompare(Scenario scenario) {
        var java = new BerthPool(scenario.berths(), scenario.segments());

        var pool =
                new PoolKey(
                        NEXT_SCHEDULE.getAndIncrement(), TravelClass.SL, QuotaType.TATKAL);
        redisAllocator.provision(pool, scenario.berths(), scenario.segments(), List.of());

        var holdIds = new ArrayList<String>();
        int nextHold = 0;

        for (int step = 0; step < scenario.ops().size(); step++) {
            Op op = scenario.ops().get(step);
            int stepNumber = step + 1;

            switch (op) {
                case Op.Allocate a -> {
                    String holdId = "h" + (nextHold++);
                    Instant now = T0.plusMillis(a.atMillis());

                    var javaResult =
                            java.allocate(a.range(), a.passengers(), holdId, now, TTL);
                    var luaResult =
                            redisAllocator.allocate(
                                    new AllocationRequest(
                                            pool, a.range(), a.passengers(), holdId, now, TTL));

                    boolean javaOk =
                            javaResult
                                    instanceof io.tatkalrush.domain.inventory.AllocationResult
                                            .Allocated;
                    boolean luaOk = luaResult instanceof AllocationResult.Allocated;

                    assertEquals(
                            javaOk,
                            luaOk,
                            () ->
                                    describe(
                                            scenario,
                                            stepNumber,
                                            op,
                                            "one implementation allocated and the other refused"));

                    if (javaOk && luaOk) {
                        var javaAlloc =
                                (io.tatkalrush.domain.inventory.AllocationResult.Allocated)
                                        javaResult;
                        var luaAlloc = (AllocationResult.Allocated) luaResult;

                        // FR-5. Not "an equally valid set" - the SAME berths. If
                        // the two picked differently, availability diverges from
                        // here on even though both answers looked correct.
                        var luaOrdinals =
                                luaAlloc.berthIds().stream()
                                        .map(id -> (int) (id - pool.scheduleId() * 1000L))
                                        .toList();
                        assertEquals(
                                javaAlloc.berthOrdinals(),
                                luaOrdinals,
                                () ->
                                        describe(
                                                scenario,
                                                stepNumber,
                                                op,
                                                "FR-5: the implementations chose different berths"));
                        holdIds.add(holdId);
                    }
                }
                case Op.Release rel -> {
                    if (!holdIds.isEmpty()) {
                        String holdId = holdIds.get(rel.holdIndex() % holdIds.size());
                        java.release(holdId);
                        redisAllocator.release(holdId);
                    }
                }
            }

            assertSameState(java, pool, scenario, stepNumber, op);
        }
    }

    /**
     * The assertion §9.2 insists on: identical state, not merely identical
     * answers.
     */
    private static void assertSameState(
            BerthPool java, PoolKey pool, Scenario scenario, int step, Op op) {

        var lua = redisAllocator.snapshot(pool);

        for (int ordinal = 0; ordinal < scenario.berths(); ordinal++) {
            long javaMask = java.maskAt(ordinal);
            long luaMask = lua.masks()[ordinal];
            int o = ordinal;
            assertEquals(
                    javaMask,
                    luaMask,
                    () ->
                            describe(scenario, step, op,
                                    "berth %d mask differs%n      java: %s%n      lua : %s"
                                            .formatted(
                                                    o,
                                                    SegmentMask.render(javaMask, scenario.segments()),
                                                    SegmentMask.render(luaMask, scenario.segments()))));
        }

        for (int seg = 0; seg < scenario.segments(); seg++) {
            int javaFree = java.freeOn(SegmentRange.of(seg, seg + 1));
            int luaFree = lua.freeCounts()[seg];
            int s = seg;
            assertEquals(
                    javaFree,
                    luaFree,
                    () ->
                            describe(scenario, step, op,
                                    "free count on segment %d differs: java=%d lua=%d"
                                            .formatted(s, javaFree, luaFree)));
        }

        // The Java side's own INV-12 self-check. If this fires, the divergence is
        // in the reference rather than in the Lua, which is a different bug and
        // worth saying so.
        java.checkInvariants();
    }

    private static String describe(Scenario scenario, int step, Op op, String problem) {
        return """
               T-7 DIVERGENCE at step %d (%s)
                 %s

               Scenario:
                 %s"""
                .formatted(step, op, problem, scenario);
    }

    @Test
    @DisplayName("the harness would notice a divergence")
    void harnessDetectsDivergence() {
        // A differential test that cannot fail is decoration, and this one gates
        // AC-1.7. Rather than corrupt the Lua, this drives the two implementations
        // apart directly: an allocation applied to Redis and not to the Java pool
        // must be caught by the very next state comparison.
        var pool = new PoolKey(NEXT_SCHEDULE.getAndIncrement(), TravelClass.SL, QuotaType.TATKAL);
        var java = new BerthPool(2, 4);
        redisAllocator.provision(pool, 2, 4, List.of());

        var scenario = new Scenario(2, 4, List.of());
        assertSameState(java, pool, scenario, 0, new Op.Release(0));

        redisAllocator.allocate(
                new AllocationRequest(pool, SegmentRange.of(0, 2), 1, "only-lua", T0, TTL));

        var error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        AssertionError.class,
                        () -> assertSameState(java, pool, scenario, 1, new Op.Release(0)));

        assertTrue(
                error.getMessage().contains("T-7 DIVERGENCE"),
                () -> "expected a divergence report, got: " + error.getMessage());
        assertTrue(
                error.getMessage().contains("mask differs"),
                () -> "the report must name what diverged, got: " + error.getMessage());
    }
}
