package io.tatkalrush.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.domain.PropertyRunner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>T-4</b> at the pool level: random allocate / release / reap sequences against
 * {@link BerthPool}, with INV-1 and INV-12 checked after <em>every</em> step.
 *
 * <p>The model is a plain map of live holds and a recomputed-from-scratch mask per
 * berth. It duplicates none of the pool's cleverness: no incremental counters, no
 * bit tricks, no lazy reaping optimisation. Where the pool maintains
 * {@code freeCount} incrementally across seven mutation paths, the model counts
 * berths. That asymmetry is the point — two implementations that share a shortcut
 * share its bugs.
 *
 * <p>It also verifies FR-5 <em>independently</em>: the model works out which
 * berths should have been chosen and asserts the pool chose exactly those. Berth
 * choice is observable behaviour, because T-7 asserts the Lua implementation picks
 * the same berths rather than an equally valid set.
 *
 * <p>This is the template for T-7. There, the second implementation is the real
 * {@code allocate.lua} running in a Testcontainers Redis instead of a map.
 */
class BerthPoolProperties {

    private static final long SEED = 20261001L;
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final long TTL_MILLIS = 120_000;

    // ---------------------------------------------------------------- model

    private record ModelHold(List<Integer> ordinals, SegmentRange range, Instant expiresAt) {}

    /** The obvious, slow, independent implementation. */
    private static final class PoolModel {
        private final int berthCount;
        private final int segmentCount;
        private final Map<String, ModelHold> holds = new LinkedHashMap<>();

        PoolModel(int berthCount, int segmentCount) {
            this.berthCount = berthCount;
            this.segmentCount = segmentCount;
        }

        void reap(Instant now) {
            holds.values().removeIf(h -> !h.expiresAt().isAfter(now));
        }

        /** Recomputed from scratch every time — no incremental state to drift. */
        long maskOf(int ordinal) {
            long m = SegmentMask.EMPTY;
            for (ModelHold h : holds.values()) {
                if (h.ordinals().contains(ordinal)) {
                    m |= h.range().mask();
                }
            }
            return m;
        }

        /** Which berths FR-5 says should be chosen, in order. */
        List<Integer> wouldChoose(SegmentRange range, int passengerCount) {
            var chosen = new ArrayList<Integer>(passengerCount);
            for (int ordinal = 0; ordinal < berthCount; ordinal++) {
                if (SegmentMask.isFree(maskOf(ordinal), range.mask())) {
                    chosen.add(ordinal);
                    if (chosen.size() == passengerCount) {
                        break;
                    }
                }
            }
            return chosen;
        }

        /** Berths free on a segment, counted rather than tracked. */
        int freeOnSegment(int segment) {
            int free = 0;
            for (int ordinal = 0; ordinal < berthCount; ordinal++) {
                if ((maskOf(ordinal) & (1L << segment)) == 0) {
                    free++;
                }
            }
            return free;
        }

        int minFree() {
            int min = Integer.MAX_VALUE;
            for (int seg = 0; seg < segmentCount; seg++) {
                min = Math.min(min, freeOnSegment(seg));
            }
            return min;
        }
    }

    // ------------------------------------------------------------ operations

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

        record Reap(long atMillis) implements Op {
            @Override
            public String toString() {
                return "REAP @" + atMillis + "ms";
            }
        }
    }

    private record Scenario(int berths, int segments, List<Op> ops) {
        @Override
        public String toString() {
            return "%d berths, %d segments, %d ops:%n    %s"
                    .formatted(
                            berths,
                            segments,
                            ops.size(),
                            String.join("\n    ", ops.stream().map(Object::toString).toList()));
        }
    }

    private static Scenario randomScenario(Random r) {
        // Small pools on purpose. Contention is the interesting state, and a
        // 72-berth pool with 10 operations never runs out of berths, so it never
        // exercises the paths that matter.
        int berths = 1 + r.nextInt(6);
        int segments = 2 + r.nextInt(6);
        int opCount = 1 + r.nextInt(14);

        // Time advances in coarse steps so holds actually expire within a
        // sequence. A clock that never passes the TTL never reaps.
        long clock = 0;
        var ops = new ArrayList<Op>(opCount);

        for (int i = 0; i < opCount; i++) {
            clock += r.nextInt(3) == 0 ? TTL_MILLIS + 1 : r.nextInt(30_000);
            int roll = r.nextInt(10);
            if (roll < 6) {
                int from = r.nextInt(segments);
                int to = from + 1 + r.nextInt(segments - from);
                ops.add(new Op.Allocate(SegmentRange.of(from, to), 1 + r.nextInt(3), clock));
            } else if (roll < 8) {
                ops.add(new Op.Release(r.nextInt(8)));
            } else {
                ops.add(new Op.Reap(clock));
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

    // ------------------------------------------------------------ properties

    @Test
    @DisplayName("T-4: the pool agrees with an independent model after every operation")
    void poolTracksTheModel() {
        PropertyRunner.check(
                "pool == model after every allocate / release / reap",
                SEED,
                500,
                BerthPoolProperties::randomScenario,
                scenario -> runScenario(scenario, true),
                BerthPoolProperties::shrink);
    }

    @Test
    @DisplayName("T-4: releasing every hold returns capacity exactly to baseline")
    void capacityReturnsToBaseline() {
        PropertyRunner.check(
                "after releasing everything, every mask is empty",
                SEED + 1,
                500,
                BerthPoolProperties::randomScenario,
                scenario -> {
                    var pool = runScenario(scenario, false);

                    // Reap far past every TTL: everything outstanding must go.
                    pool.reapExpired(T0.plusMillis(Long.MAX_VALUE / 4));

                    for (int ordinal = 0; ordinal < scenario.berths(); ordinal++) {
                        long mask = pool.maskAt(ordinal);
                        int finalOrdinal = ordinal;
                        assertEquals(
                                SegmentMask.EMPTY,
                                mask,
                                () ->
                                        "berth %d still occupied (%s) after every hold expired"
                                                .formatted(
                                                        finalOrdinal,
                                                        SegmentMask.render(
                                                                mask, scenario.segments())));
                    }

                    // "Exactly" matters: capacity that does not come back is
                    // inventory leaking out of the system, with no error raised
                    // and nothing to notice it until a train departs half empty
                    // beside a non-empty waitlist.
                    assertEquals(
                            scenario.berths(),
                            pool.remainingBerths(),
                            "capacity did not return to baseline");
                    assertEquals(0, pool.liveHoldCount());
                    pool.checkInvariants();
                },
                BerthPoolProperties::shrink);
    }

    @Test
    @DisplayName("T-4 / INV-1: a berth is never held by two overlapping ranges")
    void neverOverbooks() {
        PropertyRunner.check(
                "no berth carries two overlapping live holds",
                SEED + 2,
                500,
                BerthPoolProperties::randomScenario,
                scenario -> {
                    // This is the property the whole project exists to guarantee.
                    // It is checked here against the model's per-berth hold list,
                    // which the pool's bitmask cannot express -- the mask knows a
                    // segment is taken, not by how many bookings, so an
                    // overbooking would be invisible to the mask alone.
                    runScenario(scenario, true);
                },
                BerthPoolProperties::shrink);
    }

    // -------------------------------------------------------------- harness

    /** Runs a scenario against both pool and model, asserting agreement throughout. */
    private static BerthPool runScenario(Scenario scenario, boolean assertAgreement) {
        var pool = new BerthPool(scenario.berths(), scenario.segments());
        var model = new PoolModel(scenario.berths(), scenario.segments());
        var holdIds = new ArrayList<String>();
        int nextHold = 0;

        for (Op op : scenario.ops()) {
            switch (op) {
                case Op.Allocate a -> {
                    Instant now = T0.plusMillis(a.atMillis());
                    // The pool reaps lazily inside allocate (§9.2); the model must
                    // do the same before predicting, or the two legitimately
                    // disagree about what is free.
                    model.reap(now);

                    List<Integer> expected = model.wouldChoose(a.range(), a.passengers());
                    String holdId = "h" + (nextHold++);
                    var result = pool.allocate(a.range(), a.passengers(), holdId, now, TTL_MILLIS);

                    if (expected.size() == a.passengers()) {
                        var got =
                                org.junit.jupiter.api.Assertions.assertInstanceOf(
                                        AllocationResult.Allocated.class,
                                        result,
                                        () -> "model expected success for " + a + ", pool refused");
                        // FR-5, verified independently rather than restated.
                        assertEquals(
                                expected,
                                got.berthOrdinals(),
                                () -> "FR-5: wrong berths chosen for " + a);
                        model.holds.put(
                                holdId,
                                new ModelHold(expected, a.range(), now.plusMillis(TTL_MILLIS)));
                        holdIds.add(holdId);
                    } else {
                        org.junit.jupiter.api.Assertions.assertInstanceOf(
                                AllocationResult.Unavailable.class,
                                result,
                                () -> "model expected refusal for " + a + ", pool allocated");
                    }
                }
                case Op.Release rel -> {
                    if (!holdIds.isEmpty()) {
                        String holdId = holdIds.get(rel.holdIndex() % holdIds.size());
                        boolean poolReleased = pool.release(holdId);
                        boolean modelHad = model.holds.remove(holdId) != null;
                        assertEquals(
                                modelHad,
                                poolReleased,
                                () -> "release disagreed on " + holdId);
                    }
                }
                case Op.Reap reap -> {
                    Instant now = T0.plusMillis(reap.atMillis());
                    int before = model.holds.size();
                    model.reap(now);
                    int expectedReaped = before - model.holds.size();
                    assertEquals(expectedReaped, pool.reapExpired(now), () -> "reap count for " + reap);
                }
            }

            if (assertAgreement) {
                assertAgrees(pool, model, scenario, op);
            }
        }
        return pool;
    }

    private static void assertAgrees(BerthPool pool, PoolModel model, Scenario s, Op after) {
        for (int ordinal = 0; ordinal < s.berths(); ordinal++) {
            int o = ordinal;
            assertEquals(
                    model.maskOf(ordinal),
                    pool.maskAt(ordinal),
                    () -> "berth %d mask diverged after %s".formatted(o, after));
        }

        // INV-12, against a count rather than the pool's own incremental counter.
        for (int seg = 0; seg < s.segments(); seg++) {
            int segment = seg;
            assertEquals(
                    model.freeOnSegment(seg),
                    pool.freeOn(SegmentRange.of(seg, seg + 1)),
                    () -> "INV-12: free count on segment %d diverged after %s".formatted(segment, after));
        }
        assertEquals(model.minFree(), pool.remainingBerths(), () -> "remainingBerths after " + after);

        // The pool's own self-check, which inspects masks rather than trusting
        // the counter it maintains.
        pool.checkInvariants();

        // INV-1 proper. The bitmask cannot express this: it records that a
        // segment is taken, not by how many bookings, so a double-sold berth
        // looks identical to a singly-sold one. Only the model's per-berth hold
        // list can tell them apart -- which is why the model keeps ranges rather
        // than mirroring the mask.
        for (int ordinal = 0; ordinal < s.berths(); ordinal++) {
            final int berth = ordinal;
            var rangesOnBerth =
                    model.holds.values().stream()
                            .filter(h -> h.ordinals().contains(berth))
                            .map(ModelHold::range)
                            .toList();

            for (int i = 0; i < rangesOnBerth.size(); i++) {
                for (int j = i + 1; j < rangesOnBerth.size(); j++) {
                    var a = rangesOnBerth.get(i);
                    var b = rangesOnBerth.get(j);
                    assertTrue(
                            !a.overlaps(b),
                            () ->
                                    "INV-1 VIOLATED: berth %d holds overlapping %s and %s after %s"
                                            .formatted(berth, a, b, after));
                }
            }
        }
    }
}
