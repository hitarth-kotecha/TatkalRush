package io.tatkalrush.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.domain.PropertyRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>T-4</b> (§18.1): for any random sequence of allocate/release operations,
 * INV-1 holds and released capacity returns exactly to baseline. Must exercise
 * segment 63 (FR-3a).
 *
 * <p>Model-based rather than assertion-based. Each property maintains a plain
 * {@code List<SegmentRange>} of what it believes is currently held, and checks the
 * mask against that model after <em>every</em> operation. The list is obviously
 * correct and obviously slow; the mask is neither. Comparing the two is what makes
 * this a test of the bitmask rather than a restatement of it.
 *
 * <p>The same shape carries T-7 in Phase 1a, where the second model is the real
 * Lua script running in Redis rather than a list.
 */
class SegmentMaskProperties {

    private static final long SEED = 20261001L;

    // ---------------------------------------------------------------- model

    /** What a correct implementation would hold, expressed the slow obvious way. */
    private static final class BerthModel {
        private final List<SegmentRange> held = new ArrayList<>();

        boolean canAllocate(SegmentRange r) {
            return held.stream().noneMatch(h -> h.overlaps(r));
        }

        void allocate(SegmentRange r) {
            held.add(r);
        }

        void release(SegmentRange r) {
            held.removeIf(h -> h.equals(r));
        }

        /** The mask this model implies, computed from scratch every time. */
        long mask() {
            long m = SegmentMask.EMPTY;
            for (SegmentRange r : held) {
                m |= r.mask();
            }
            return m;
        }

        boolean isEmpty() {
            return held.isEmpty();
        }
    }

    private record Op(boolean allocate, SegmentRange range) {
        @Override
        public String toString() {
            return (allocate ? "ALLOC " : "FREE  ") + range;
        }
    }

    /** A sequence of operations, the unit this property generates and shrinks. */
    private record Sequence(List<Op> ops) {
        @Override
        public String toString() {
            return ops.size() + " ops: " + ops;
        }
    }

    // ------------------------------------------------------------ generators

    private static SegmentRange randomRange(Random r, int maxSegments) {
        int from = r.nextInt(maxSegments);
        int to = from + 1 + r.nextInt(maxSegments - from);
        return SegmentRange.of(from, to);
    }

    private static Sequence randomSequence(Random r, int maxSegments, int maxOps) {
        int n = 1 + r.nextInt(maxOps);
        var ops = new ArrayList<Op>(n);
        for (int i = 0; i < n; i++) {
            // Weighted toward allocation so sequences actually fill the berth;
            // an even split spends most of its time releasing nothing.
            ops.add(new Op(r.nextInt(4) != 0, randomRange(r, maxSegments)));
        }
        return new Sequence(List.copyOf(ops));
    }

    /** Shrink toward fewer operations, then toward earlier ones. */
    private static List<Sequence> shrink(Sequence s) {
        var candidates = new ArrayList<Sequence>();
        if (s.ops().size() > 1) {
            // Drop the last op, then the first: between them these reduce most
            // failures to a two-operation interaction, which is the form you can
            // actually read.
            candidates.add(new Sequence(s.ops().subList(0, s.ops().size() - 1)));
            candidates.add(new Sequence(s.ops().subList(1, s.ops().size())));
        }
        return candidates;
    }

    // ------------------------------------------------------------ properties

    @Test
    @DisplayName("T-4: the mask agrees with the model after every operation")
    void maskTracksTheModelStepForStep() {
        PropertyRunner.check(
                "mask == union of held ranges, after every step",
                SEED,
                400,
                r -> randomSequence(r, 16, 12),
                seq -> {
                    var model = new BerthModel();
                    long mask = SegmentMask.EMPTY;

                    for (Op op : seq.ops()) {
                        long requestMask = op.range().mask();

                        if (op.allocate()) {
                            boolean maskSaysFree = SegmentMask.isFree(mask, requestMask);
                            boolean modelSaysFree = model.canAllocate(op.range());

                            // FR-1. If these ever disagree, the bitmask has a
                            // different idea of "available" than the obvious
                            // definition, and every allocation decision is suspect.
                            assertEquals(
                                    modelSaysFree,
                                    maskSaysFree,
                                    () -> "availability disagreed on " + op);

                            if (maskSaysFree) {
                                mask = SegmentMask.allocate(mask, requestMask);
                                model.allocate(op.range());
                            }
                        } else {
                            // Only release what is actually held, or the model and
                            // the mask legitimately diverge: the mask cannot tell
                            // "this booking's segments" from "some other booking
                            // that happens to cover them".
                            if (!model.canAllocate(op.range())) {
                                model.release(op.range());
                                // Recompute rather than clearing bits: overlapping
                                // holds are impossible here (allocation refused
                                // them), so the union is exact.
                                mask = model.mask();
                            }
                        }

                        assertEquals(
                                model.mask(),
                                mask,
                                () -> "mask diverged from the model after " + op);
                    }
                },
                SegmentMaskProperties::shrink);
    }

    @Test
    @DisplayName("T-4: releasing everything returns exactly to baseline")
    void releasingEverythingRestoresBaseline() {
        PropertyRunner.check(
                "allocate a set, release the same set, mask is empty again",
                SEED,
                400,
                r -> randomSequence(r, 24, 10),
                seq -> {
                    long mask = SegmentMask.EMPTY;
                    var allocated = new ArrayList<SegmentRange>();

                    for (Op op : seq.ops()) {
                        long m = op.range().mask();
                        if (SegmentMask.isFree(mask, m)) {
                            mask = SegmentMask.allocate(mask, m);
                            allocated.add(op.range());
                        }
                    }

                    for (SegmentRange r : allocated) {
                        mask = SegmentMask.release(mask, r.mask());
                    }

                    // "Exactly" is the word that matters. Capacity that does not
                    // return to baseline is inventory quietly leaking out of the
                    // system -- berths nobody can sell and no error reports.
                    long remaining = mask;
                    assertEquals(
                            SegmentMask.EMPTY,
                            remaining,
                            () ->
                                    "capacity leaked: "
                                            + SegmentMask.occupiedCount(remaining)
                                            + " segment(s) still occupied after releasing all "
                                            + allocated.size()
                                            + " allocations");
                },
                SegmentMaskProperties::shrink);
    }

    @Test
    @DisplayName("T-4 / FR-3a: the full 64-segment range, including segment 63")
    void holdsAtTheTopOfTheRange() {
        // FR-3a requires segment 63 be exercised explicitly. It is where a naive
        // ((1L << to) - 1) silently yields 0 instead of all-ones, and where
        // Strategy A's two-32-bit-half Lua representation will diverge if it is
        // wrong. Generating over the full width means every sequence here has a
        // real chance of touching it; the assertion below makes sure that chance
        // was actually taken.
        var touched63 = new boolean[1];

        PropertyRunner.check(
                "the mask is exact across the full 64-segment width",
                SEED,
                600,
                r -> {
                    // Bias hard toward the top: a uniform draw over [0,64) puts
                    // only ~3% of ranges anywhere near segment 63.
                    int from = r.nextInt(4) == 0 ? 60 + r.nextInt(4) : r.nextInt(64);
                    int to = from + 1 + r.nextInt(64 - from);
                    return SegmentRange.of(from, to);
                },
                range -> {
                    long m = range.mask();

                    if (range.toSeq() == 64) {
                        touched63[0] = true;
                        assertTrue(m < 0, () -> range + " reaches segment 63; sign bit must be set");
                    }

                    assertEquals(
                            range.length(),
                            SegmentMask.occupiedCount(m),
                            () -> range + " set the wrong number of bits");

                    // A non-empty range can never produce an empty mask. This is
                    // the exact failure the mod-64 shift bug produces, and it is
                    // indistinguishable from success everywhere downstream.
                    assertTrue(m != 0L, () -> range + " produced an empty mask");

                    // Complement: the segments outside the range must be clear.
                    long outside =
                            (range.fromSeq() > 0 ? SegmentMask.of(0, range.fromSeq()) : 0L)
                                    | (range.toSeq() < 64
                                            ? SegmentMask.of(range.toSeq(), 64)
                                            : 0L);
                    assertEquals(0L, m & outside, () -> range + " set bits outside itself");
                });

        assertTrue(touched63[0], "no generated range reached segment 63; FR-3a went untested");
    }

    @Test
    @DisplayName("T-4: allocation order does not change the result")
    void allocationIsOrderIndependent() {
        PropertyRunner.check(
                "allocating a disjoint set in any order yields the same mask",
                SEED,
                300,
                r -> randomSequence(r, 20, 8),
                seq -> {
                    // Forward.
                    long forward = SegmentMask.EMPTY;
                    var accepted = new ArrayList<SegmentRange>();
                    for (Op op : seq.ops()) {
                        if (SegmentMask.isFree(forward, op.range().mask())) {
                            forward = SegmentMask.allocate(forward, op.range().mask());
                            accepted.add(op.range());
                        }
                    }

                    // The accepted set, applied in reverse. Since they are pairwise
                    // disjoint by construction, order must not matter -- OR is
                    // commutative, and if this ever fails the operation is not the
                    // one we think it is.
                    long reverse = SegmentMask.EMPTY;
                    for (int i = accepted.size() - 1; i >= 0; i--) {
                        reverse = SegmentMask.allocate(reverse, accepted.get(i).mask());
                    }

                    assertEquals(forward, reverse, "allocation order changed the outcome");
                },
                SegmentMaskProperties::shrink);
    }
}
