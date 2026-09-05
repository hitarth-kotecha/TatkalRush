package io.tatkalrush.domain.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The allocation algorithm of Appendix A, over one quota pool.
 *
 * <p><b>This class is the reference specification.</b> Strategy B calls it
 * directly on its single writer thread; Strategy A reimplements the same algorithm
 * in Lua inside Redis, because it cannot call Java from there (§8.2, §9.1). T-7
 * runs random operation sequences against both and asserts they agree after every
 * step. When the two disagree, this is right by definition — so its behaviour is
 * a contract, not an implementation detail. In particular the <em>choice of
 * berth</em> is observable: FR-5's lowest-ordinal-first rule means T-7 can assert
 * the same berths were picked, not merely an equally valid set.
 *
 * <h2>Not thread-safe, deliberately</h2>
 *
 * <p>That looks wrong in a project about concurrency, and it is the opposite.
 * Strategy B guarantees exactly one writer per partition (§9.3); Strategy A runs
 * inside the single-threaded Redis process. Synchronising here would pretend the
 * domain solves a problem the <em>architecture</em> already solved, and it would
 * slow the reference implementation that §9.4 benchmarks against — putting a lock
 * in the measurement of a design whose whole claim is that it does not need one.
 * The thread-safety belongs to the strategy, not to the algorithm.
 *
 * <h2>Free counts are stored, not derived</h2>
 *
 * <p>Availability search needs "how many berths are free on the worst segment of
 * this route" without scanning every berth (FR-13, FR-32's {@code remaining_berths}).
 * That is a denormalisation, and denormalised data drifts — seven different code
 * paths mutate it. INV-12 exists because of this, and {@link #checkInvariants()}
 * lets a test assert it at any point rather than only after a run.
 */
public final class BerthPool {

    private final int segmentCount;

    /**
     * Occupancy per berth, indexed by <b>pool ordinal</b>, not berth id. The
     * ordinal is the bit position the allocator and the Lua script agree on;
     * mapping it back to a database berth id is an adapter's job.
     *
     * <p>A primitive array, not a {@code List<Long>}: Strategy B checkpoints this
     * by copying it (~5.6 KB) off the consumer thread (§9.3, DD-013), and it is
     * read on the hot path of every allocation attempt during a spike.
     */
    private final long[] masks;

    /** Berths free on each segment. Maintained incrementally; INV-12 checks it. */
    private final int[] freeCount;

    /**
     * Live holds. Insertion-ordered so reaping is deterministic — two runs of the
     * same operation sequence must reap in the same order, or T-7 and the seed
     * determinism guarantee both lose their meaning.
     */
    private final Map<String, Hold> holds = new LinkedHashMap<>();

    private record Hold(List<Integer> berthOrdinals, long requestMask, Instant expiresAt) {}

    /**
     * @param berthCount berths in this pool
     * @param segmentCount segments on the route, at most 64 (FR-3)
     */
    public BerthPool(int berthCount, int segmentCount) {
        if (berthCount < 0) {
            throw new IllegalArgumentException("berthCount must be >= 0, got " + berthCount);
        }
        if (segmentCount < 1 || segmentCount > SegmentRange.MAX_SEGMENTS) {
            throw new IllegalArgumentException(
                    "segmentCount must be 1.." + SegmentRange.MAX_SEGMENTS + " (FR-3), got "
                            + segmentCount);
        }
        this.segmentCount = segmentCount;
        this.masks = new long[berthCount];
        this.freeCount = new int[segmentCount];
        // Seeded to the full berth count at pool creation, in the same place the
        // masks are initialised. Initialising these two separately is how they
        // start out of step (DD-012).
        java.util.Arrays.fill(this.freeCount, berthCount);
    }

    // ------------------------------------------------------------- allocation

    /**
     * Appendix A. Reaps expired holds, then allocates {@code passengerCount}
     * berths for {@code range}, all or nothing (FR-6).
     *
     * @param now the caller's clock reading. Passed rather than read, so the
     *     algorithm is a pure function of its inputs and FR-31's injected
     *     {@code Clock} has somewhere to inject to.
     */
    public AllocationResult allocate(
            SegmentRange range, int passengerCount, String holdId, Instant now, long ttlMillis) {

        if (passengerCount < 1) {
            throw new IllegalArgumentException("passengerCount must be >= 1, got " + passengerCount);
        }
        if (range.toSeq() > segmentCount) {
            throw new IllegalArgumentException(
                    "range %s exceeds the route's %d segments".formatted(range, segmentCount));
        }
        if (holds.containsKey(holdId)) {
            throw new IllegalStateException("hold already exists: " + holdId);
        }

        // Lazy reap, BEFORE the scan (§9.2). Without it a hold that expired but
        // has not been swept still occupies its berths, and a berth that is
        // genuinely free reads as taken - the system refusing bookings it should
        // accept, with no error anywhere.
        reapExpired(now);

        final long requestMask = range.mask();

        // FR-5: lowest ordinal that satisfies FR-1, scanning in order. Determinism
        // here is not tidiness - it is what makes the two strategies comparable
        // and their differential test assertable.
        var chosen = new ArrayList<Integer>(passengerCount);
        for (int ordinal = 0; ordinal < masks.length; ordinal++) {
            if (SegmentMask.isFree(masks[ordinal], requestMask)) {
                chosen.add(ordinal);
                if (chosen.size() == passengerCount) {
                    break;
                }
            }
        }

        // FR-6: all or nothing. A partial allocation would leave berths held for a
        // booking that failed - precisely the orphaned-hold failure §1 claims this
        // system does not have.
        if (chosen.size() < passengerCount) {
            return new AllocationResult.Unavailable(chosen.size(), passengerCount);
        }

        for (int ordinal : chosen) {
            masks[ordinal] = SegmentMask.allocate(masks[ordinal], requestMask);
        }
        adjustFreeCounts(range, -passengerCount);

        holds.put(holdId, new Hold(List.copyOf(chosen), requestMask, now.plusMillis(ttlMillis)));
        return new AllocationResult.Allocated(holdId, chosen, range);
    }

    /**
     * Releases a hold's berths: confirmed-then-cancelled, or an explicit release.
     *
     * <p>Idempotent. Release arrives from hold expiry, cancellation and chart
     * preparation, and the lazy reaper can race a caller for the same hold — so
     * "already gone" is a normal outcome, not an error.
     *
     * @return whether a live hold was found and released
     */
    public boolean release(String holdId) {
        Hold hold = holds.remove(holdId);
        if (hold == null) {
            return false;
        }
        releaseHold(hold);
        return true;
    }

    /**
     * Promotes a hold into a permanent allocation: the berths stay occupied, but
     * the expiry clock stops.
     *
     * <p>Distinct from {@link #release} in exactly the way that matters. Release
     * frees the berths; confirm keeps them and removes the hold from the reaper's
     * reach. Without this a confirmed booking's berths would be swept the moment
     * its original TTL passed, releasing seats a passenger has paid for — and
     * INV-4 would find the orphan long after the customer did.
     *
     * @return whether a live hold was found and confirmed. {@code false} means it
     *     had already expired, which FR-24 treats as benign rather than as an
     *     error: the caller auto-refunds with reason {@code HOLD_EXPIRED}.
     */
    public boolean confirm(String holdId) {
        return holds.remove(holdId) != null;
    }

    /** Berth ordinals a live hold occupies, or empty if it is gone. */
    public List<Integer> berthsOf(String holdId) {
        Hold hold = holds.get(holdId);
        return hold == null ? List.of() : hold.berthOrdinals();
    }

    /**
     * Sweeps holds whose TTL has passed (§9.2).
     *
     * <p>Called automatically at the start of every {@link #allocate}, and
     * exposed so a background reaper can drive it too. Both paths existing is
     * deliberate: the lazy call keeps correctness on the read path, and the
     * background call stops an idle pool holding expired berths indefinitely.
     *
     * @return how many holds were reaped
     */
    public int reapExpired(Instant now) {
        if (holds.isEmpty()) {
            return 0;
        }
        int reaped = 0;
        var iterator = holds.entrySet().iterator();
        while (iterator.hasNext()) {
            Hold hold = iterator.next().getValue();
            // Expiry is inclusive: a hold expiring exactly now is expired. The
            // boundary has to be picked, and picking "expired" fails toward
            // releasing inventory rather than toward holding it.
            if (!hold.expiresAt().isAfter(now)) {
                iterator.remove();
                releaseHold(hold);
                reaped++;
            }
        }
        return reaped;
    }

    /**
     * Frees berths belonging to a <em>confirmed</em> booking (FR-43).
     *
     * <p>{@link #confirm} deletes the hold record, so {@link #release} can no
     * longer reach these berths — which is the point of confirming, since the
     * reaper must never sweep a berth someone has paid for. Cancellation therefore
     * has to say which berths, from the booking row that owns them.
     *
     * <h2>It counts what it clears, not what it was asked to clear</h2>
     *
     * <p>{@link #releaseHold} may increment a segment's free count by the whole
     * berth count, because it created the hold and knows every one of those berths
     * had every bit of the range set. Nothing guarantees that here. This runs from
     * a user cancellation, a retry of one, and a chart-time sweep, and it must be
     * safe to run twice — so a bit that is already clear must contribute nothing.
     *
     * <p>Hence the per-segment test below rather than a single multiply. Getting it
     * wrong inflates {@code freeCount}: the pool reports berths it does not have,
     * INV-12 fails at the next quiesce point, and the number it corrupts is
     * upstream of the metric §9.4's conclusion rests on.
     *
     * @return how many (berth, segment) bits were actually cleared. Zero means the
     *     berths were already free, which is a successful no-op rather than an
     *     error.
     */
    public int releaseConfirmed(SegmentRange range, List<Integer> berthOrdinals) {
        long requestMask = SegmentMask.of(range.fromSeq(), range.toSeq());
        int cleared = 0;

        for (int seg = range.fromSeq(); seg < range.toSeq(); seg++) {
            long segmentBit = 1L << seg;
            int freedOnSegment = 0;

            for (int ordinal : berthOrdinals) {
                if ((masks[ordinal] & segmentBit) != 0) {
                    freedOnSegment++;
                }
            }

            freeCount[seg] += freedOnSegment;
            cleared += freedOnSegment;
        }

        // Cleared after counting, so the count observes the state it is describing.
        // AND NOT: another booking occupying a different leg of the same berth is
        // untouched, which is the whole of what segment-wise inventory buys.
        for (int ordinal : berthOrdinals) {
            masks[ordinal] = SegmentMask.release(masks[ordinal], requestMask);
        }

        return cleared;
    }

    private void releaseHold(Hold hold) {
        for (int ordinal : hold.berthOrdinals()) {
            masks[ordinal] = SegmentMask.release(masks[ordinal], hold.requestMask());
        }
        adjustFreeCountsByMask(hold.requestMask(), hold.berthOrdinals().size());
    }

    private void adjustFreeCounts(SegmentRange range, int delta) {
        for (int seg = range.fromSeq(); seg < range.toSeq(); seg++) {
            freeCount[seg] += delta;
        }
    }

    private void adjustFreeCountsByMask(long mask, int delta) {
        for (int seg = 0; seg < segmentCount; seg++) {
            if ((mask & (1L << seg)) != 0) {
                freeCount[seg] += delta;
            }
        }
    }

    // ----------------------------------------------------------------- reads

    /** Berths free on the requested range's <em>worst</em> segment. */
    public int freeOn(SegmentRange range) {
        int min = Integer.MAX_VALUE;
        for (int seg = range.fromSeq(); seg < range.toSeq(); seg++) {
            min = Math.min(min, freeCount[seg]);
        }
        return min;
    }

    /**
     * FR-32's {@code remaining_berths}: the count allocatable for <em>any</em>
     * range, being the minimum across every segment.
     *
     * <p>Deliberately conservative. On a route where most segments have space but
     * one is nearly full, this reports the whole train as nearly full, so
     * admission control admits fewer users than strictly necessary. That is the
     * correct direction to err — over-admission is the failure the component
     * exists to prevent (DD-015).
     */
    public int remainingBerths() {
        int min = Integer.MAX_VALUE;
        for (int seg = 0; seg < segmentCount; seg++) {
            min = Math.min(min, freeCount[seg]);
        }
        return min;
    }

    public int berthCount() {
        return masks.length;
    }

    public int segmentCount() {
        return segmentCount;
    }

    public int liveHoldCount() {
        return holds.size();
    }

    /** Occupancy of one berth. Exposed for checkpointing and for T-7's comparison. */
    public long maskAt(int ordinal) {
        return masks[ordinal];
    }

    /**
     * A copy of the mask array.
     *
     * <p>A <b>copy</b>, not the array: Strategy B checkpoints this off the
     * consumer thread, and a shared array tears mid-mutation — the snapshot would
     * then describe a state that never existed (DD-013).
     */
    public long[] snapshotMasks() {
        return masks.clone();
    }

    // ------------------------------------------------------------ invariants

    /**
     * INV-12: the stored free counts match what the masks actually say.
     *
     * <p>Seven code paths mutate {@code freeCount} — allocate, lazy reap,
     * background reaper, release, cancel, promote and chart. Drift inflates
     * {@code SEAT_UNAVAILABLE}, which FR-51 excludes from the error budget, so a
     * drifting counter manufactures a signal indistinguishable from real
     * contention and corrupts §9.4's conclusion. Cheap enough to assert in tests
     * after every operation.
     *
     * @throws IllegalStateException naming the first segment that disagrees
     */
    public void checkInvariants() {
        for (int seg = 0; seg < segmentCount; seg++) {
            long bit = 1L << seg;
            int actuallyFree = 0;
            for (long mask : masks) {
                if ((mask & bit) == 0) {
                    actuallyFree++;
                }
            }
            if (actuallyFree != freeCount[seg]) {
                throw new IllegalStateException(
                        "INV-12 violated on segment %d: freeCount says %d, masks say %d"
                                .formatted(seg, freeCount[seg], actuallyFree));
            }
        }
    }
}
