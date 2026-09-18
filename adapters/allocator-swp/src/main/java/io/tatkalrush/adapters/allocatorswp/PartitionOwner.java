package io.tatkalrush.adapters.allocatorswp;

import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AvailabilitySnapshot;
import io.tatkalrush.application.ports.ConfirmResult;
import io.tatkalrush.domain.inventory.BerthPool;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single-writer engine of one Kafka partition (§9.3, milestone 1).
 *
 * <p>Everything this class touches is reached from exactly one thread — the
 * consumer thread Kafka assigned this partition to. That is what lets it call
 * {@link BerthPool} directly with no lock, exactly as §9.3 specifies: "No locks,
 * no CAS, no transactions on the hot path — the allocation is an array scan and a
 * bitwise OR." Do not add synchronization here; if two threads ever call into the
 * same instance, the bug is upstream, in whatever handed two consumers the same
 * partition.
 *
 * <h2>One owner, many pools</h2>
 *
 * <p>A Kafka partition and a {@link PoolKey} are not the same thing: partition
 * count is capped at the machine's core count (§8.3, twelve on this laptop),
 * while a schedule exists per (train, date) times five classes times two quotas.
 * Many pools hash onto the same partition, so this owner keeps a {@link BerthPool}
 * per {@link PoolKey} it has been asked to provision, exactly as
 * {@code RedisSeatAllocator} keeps state per pool inside one Redis instance.
 *
 * <h2>{@code holdId -> pool} is a milestone-1 simplification</h2>
 *
 * <p>{@link #release} and {@link #confirm} take only a {@code holdId} — the port
 * gives them nothing else, because the caller (a reaper sweep, a payment webhook)
 * often does not have the pool to hand. Strategy A resolves this with a Redis key
 * ({@code holdpool:}) that outlives the hold and is visible to every replica.
 * This class resolves it from an in-heap map instead, which is correct only for
 * the JVM that created the hold. A release routed by a different replica finds
 * nothing here, silently degrading into the hold's own TTL expiry — safe (FR-24
 * already treats "already gone" as benign) but not prompt. A durable,
 * cross-replica index is deferred to the milestone that adds checkpointing and
 * multi-instance rebalancing (§9.3's replay work), because that is also where the
 * project first has to reason about state that must survive one JVM's death.
 */
public final class PartitionOwner {

    private final Map<PoolKey, BerthPool> pools = new HashMap<>();
    private final Map<PoolKey, long[]> berthIdsByPool = new HashMap<>();
    private final Map<String, PoolKey> poolByHold = new HashMap<>();
    private final Map<String, Instant> holdExpiryByHold = new HashMap<>();

    /**
     * Creates a fresh, fully-free pool. Equivalent to
     * {@link #provision(PoolKey, int, int, List, List)} with an empty
     * {@code occupiedMasks} — see that overload for why a non-empty one exists
     * at all.
     */
    public void provision(PoolKey pool, int berthCount, int segmentCount, List<Long> berthIds) {
        provision(pool, berthCount, segmentCount, berthIds, List.of());
    }

    /**
     * Creates or replaces a pool's in-memory state (milestone 7).
     *
     * @param berthIds {@code berths.id} for each pool ordinal, in ordinal order —
     *     required for the same reason {@code RedisSeatAllocator.provision}
     *     requires it: an owner that invents ids will, and an invented id inside
     *     the valid range of {@code berths.id} corrupts a booking silently.
     * @param occupiedMasks confirmed occupancy per ordinal, or empty for a fresh
     *     pool. Not the same thing as re-running {@link #allocate} for each
     *     confirmed booking: this sets mask bits directly with no live hold
     *     attached, exactly matching what {@link #confirm} leaves behind — a
     *     confirmed booking has no hold record, only occupied bits. Needed at
     *     all because provisioning a pool that already has confirmed bookings
     *     (re-running the warmup tool, or recovering a pool a checkpoint does
     *     not yet cover) must not silently re-open berths someone has paid for.
     * @throws IllegalArgumentException if {@code occupiedMasks} is non-empty and
     *     not exactly {@code berthCount} entries — a partial mask array would
     *     leave some ordinals with no recorded occupancy at all, which is a
     *     silent version of the same bug the missing-mapping check below guards
     *     against.
     */
    public void provision(
            PoolKey pool, int berthCount, int segmentCount, List<Long> berthIds, List<Long> occupiedMasks) {
        if (berthIds.size() != berthCount) {
            throw new IllegalArgumentException(
                    "pool %s: %d berth ids for %d berths"
                            .formatted(pool, berthIds.size(), berthCount));
        }
        if (!occupiedMasks.isEmpty() && occupiedMasks.size() != berthCount) {
            throw new IllegalArgumentException(
                    "pool %s: %d occupied masks for %d berths"
                            .formatted(pool, occupiedMasks.size(), berthCount));
        }

        BerthPool berthPool;
        if (occupiedMasks.isEmpty()) {
            berthPool = new BerthPool(berthCount, segmentCount);
        } else {
            long[] masks = new long[occupiedMasks.size()];
            for (int i = 0; i < masks.length; i++) {
                masks[i] = occupiedMasks.get(i);
            }
            // No live holds: only CONFIRMED occupancy is ever replayed into a
            // provision call. In-flight holds are not durable state to restore
            // from (§9.2) - the same rule RedisSeatAllocator.provision follows.
            berthPool = BerthPool.restore(segmentCount, masks, List.of());
        }
        pools.put(pool, berthPool);

        long[] ids = new long[berthIds.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = berthIds.get(i);
        }
        berthIdsByPool.put(pool, ids);
    }

    // -------------------------------------------------------------- allocate

    public io.tatkalrush.application.ports.AllocationResult allocate(AllocationRequest request) {
        BerthPool pool = poolOf(request.pool());

        var domainResult =
                pool.allocate(
                        request.range(),
                        request.passengerCount(),
                        request.holdId(),
                        request.now(),
                        request.ttlMillis());

        if (domainResult
                instanceof io.tatkalrush.domain.inventory.AllocationResult.Unavailable u) {
            return new io.tatkalrush.application.ports.AllocationResult.Unavailable(
                    u.available(), u.requested());
        }

        var allocated = (io.tatkalrush.domain.inventory.AllocationResult.Allocated) domainResult;
        Instant expiresAt = request.now().plusMillis(request.ttlMillis());

        poolByHold.put(request.holdId(), request.pool());
        holdExpiryByHold.put(request.holdId(), expiresAt);

        List<Long> berthIds = toBerthIds(request.pool(), allocated.berthOrdinals());
        return new io.tatkalrush.application.ports.AllocationResult.Allocated(
                request.holdId(), berthIds, request.range(), expiresAt);
    }

    // --------------------------------------------------------------- release

    /** No-op for an unknown hold — see the class-level note on {@code holdId -> pool}. */
    public void release(String holdId) {
        PoolKey pool = poolByHold.remove(holdId);
        holdExpiryByHold.remove(holdId);
        if (pool == null) {
            return;
        }
        pools.get(pool).release(holdId);
    }

    public int releaseConfirmed(PoolKey pool, SegmentRange range, List<Long> berthIds) {
        if (berthIds.isEmpty()) {
            return 0;
        }
        BerthPool berthPool = poolOf(pool);
        List<Integer> ordinals = new ArrayList<>(berthIds.size());
        for (long berthId : berthIds) {
            ordinals.add(ordinalOf(pool, berthId));
        }
        return berthPool.releaseConfirmed(range, ordinals);
    }

    // --------------------------------------------------------------- confirm

    public ConfirmResult confirm(String holdId, long bookingId) {
        PoolKey pool = poolByHold.get(holdId);
        if (pool == null) {
            return new ConfirmResult.HoldExpired(holdId);
        }

        BerthPool berthPool = pools.get(pool);
        // Read before confirm(): confirm() removes the hold record, and with it
        // the only handle on which berths it named (mirrors RedisSeatAllocator).
        List<Integer> ordinals = berthPool.berthsOf(holdId);
        boolean stillLive = berthPool.confirm(holdId);

        poolByHold.remove(holdId);
        holdExpiryByHold.remove(holdId);

        if (!stillLive) {
            return new ConfirmResult.HoldExpired(holdId);
        }
        return new ConfirmResult.Confirmed(bookingId, toBerthIds(pool, ordinals));
    }

    // ---------------------------------------------------------- availability

    public AvailabilitySnapshot availability(PoolKey pool, SegmentRange range) {
        int free = poolOf(pool).freeOn(range);
        return new AvailabilitySnapshot(pool, range, free, false);
    }

    // ------------------------------------------------------------------ reap

    /**
     * Reaps every pool this owner holds (§13.2). Unlike Strategy A's {@code SCAN}
     * over Redis keys, this owner already knows every pool it owns — {@link #pools}
     * is the discovery mechanism.
     */
    public int reapExpired(Instant now) {
        int reaped = 0;
        for (BerthPool pool : pools.values()) {
            reaped += pool.reapExpired(now);
        }

        // Bookkeeping cleanup, independent of the domain's own reap above (which
        // may also have reaped holds lazily during an allocate() this class never
        // sees the internals of). Same inclusive boundary BerthPool uses, so the
        // two never disagree about which holds are gone.
        var it = holdExpiryByHold.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!entry.getValue().isAfter(now)) {
                it.remove();
                poolByHold.remove(entry.getKey());
            }
        }

        return reaped;
    }

    // ----------------------------------------------------------------- reads

    /** The pool a live hold belongs to, or {@code null} — used by the Kafka adapter to route. */
    PoolKey poolOfHold(String holdId) {
        return poolByHold.get(holdId);
    }

    boolean owns(PoolKey pool) {
        return pools.containsKey(pool);
    }

    // -------------------------------------------------------- replay (§9.3 M3)

    /**
     * Applies one WAL entry exactly as it happened, reusing the same methods a
     * live command would call — except {@link PartitionEvent.Allocated}, which
     * must restore the recorded berths rather than re-run the search (see
     * {@link BerthPool#restoreHold}). Safe only for in-order, single-pass replay
     * of the authoritative log — it is not idempotent against re-applying an
     * event that has already been applied.
     */
    void applyEvent(PartitionEvent event) {
        switch (event) {
            case PartitionEvent.Provisioned e ->
                    provision(e.pool(), e.berthCount(), e.segmentCount(), e.berthIds(), e.occupiedMasks());
            case PartitionEvent.Allocated e ->
                    restoreAllocation(e.pool(), e.holdId(), e.range(), e.berthIds(), e.expiresAt());
            case PartitionEvent.Released e -> release(e.holdId());
            case PartitionEvent.Confirmed e -> confirm(e.holdId(), e.bookingId());
            case PartitionEvent.ReleaseConfirmed e -> releaseConfirmed(e.pool(), e.range(), e.berthIds());
            case PartitionEvent.Reaped e -> reapExpired(e.now());
        }
    }

    private void restoreAllocation(
            PoolKey pool, String holdId, SegmentRange range, List<Long> berthIds, Instant expiresAt) {
        BerthPool berthPool = poolOf(pool);
        List<Integer> ordinals = new ArrayList<>(berthIds.size());
        for (long berthId : berthIds) {
            ordinals.add(ordinalOf(pool, berthId));
        }
        berthPool.restoreHold(holdId, ordinals, range, expiresAt);
        poolByHold.put(holdId, pool);
        holdExpiryByHold.put(holdId, expiresAt);
    }

    // ----------------------------------------------------- checkpoint (§9.3 M3)

    /** One pool's checkpointable state, encoded (§9.3, DD-013). */
    byte[] snapshotOf(PoolKey pool) {
        BerthPool berthPool = poolOf(pool);
        return PoolSnapshotCodec.encode(
                berthPool.segmentCount(), berthPool.snapshotMasks(), berthPool.liveHolds());
    }

    /**
     * Loads a checkpoint's bytes into an <b>already-provisioned</b> pool.
     *
     * <p>Not yet called by replay (see {@code KafkaSeatAllocator}'s class
     * Javadoc for why: nothing yet records which pools live on which Kafka
     * partition, so a newly-assigned owner has no way to know which checkpoints
     * to load before it has replayed anything). Exists so the checkpoint's own
     * round trip — write, then reconstruct an identical {@link BerthPool} — is
     * independently testable ahead of the milestone that wires it in.
     */
    void restorePool(PoolKey pool, byte[] snapshot) {
        if (!pools.containsKey(pool)) {
            throw new IllegalStateException("restorePool: pool not provisioned: " + pool);
        }
        var decoded = PoolSnapshotCodec.decode(snapshot);
        pools.put(pool, BerthPool.restore(decoded.segmentCount(), decoded.masks(), decoded.holds()));
        for (BerthPool.HoldSnapshot hold : decoded.holds()) {
            poolByHold.put(hold.holdId(), pool);
            holdExpiryByHold.put(hold.holdId(), hold.expiresAt());
        }
    }

    // -------------------------------------------------------------- mapping

    private BerthPool poolOf(PoolKey key) {
        BerthPool pool = pools.get(key);
        if (pool == null) {
            throw new IllegalStateException("pool not provisioned on this owner: " + key);
        }
        return pool;
    }

    private List<Long> toBerthIds(PoolKey pool, List<Integer> ordinals) {
        long[] ids = berthIdsByPool.get(pool);
        var berthIds = new ArrayList<Long>(ordinals.size());
        for (int ordinal : ordinals) {
            berthIds.add(ids[ordinal]);
        }
        return berthIds;
    }

    private int ordinalOf(PoolKey pool, long berthId) {
        long[] ids = berthIdsByPool.get(pool);
        for (int ordinal = 0; ordinal < ids.length; ordinal++) {
            if (ids[ordinal] == berthId) {
                return ordinal;
            }
        }
        throw new IllegalArgumentException("berth %d does not belong to pool %s".formatted(berthId, pool));
    }
}
