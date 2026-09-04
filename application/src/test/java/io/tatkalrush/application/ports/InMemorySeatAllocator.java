package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.AllocationResult.Allocated;
import io.tatkalrush.domain.inventory.BerthPool;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory {@link SeatAllocator} over {@link BerthPool}.
 *
 * <p><b>Not a production strategy.</b> It exists so the contract suite has an
 * implementation to be validated against before Strategy A's Lua script exists —
 * a contract suite nothing has ever passed is a guess about what the interface
 * means. It also gives use-case tests a fast fake that needs no container.
 *
 * <p><b>On the lock.</b> {@link BerthPool} is deliberately not thread-safe: the
 * real strategies get their serialisation from the architecture, not the
 * algorithm. Redis is single-threaded; Strategy B owns a partition on one
 * consumer thread. This fake has neither, so it takes a per-pool lock to supply
 * the same guarantee by the crudest available means — and that is the honest
 * framing. The lock here is a stand-in for a property the real implementations
 * get structurally, which is also why this class must never be mistaken for a
 * measurement of either of them.
 */
public final class InMemorySeatAllocator implements SeatAllocator {

    private record PoolState(BerthPool pool, List<Long> berthIds, Object lock) {}

    private final Map<PoolKey, PoolState> pools = new ConcurrentHashMap<>();

    /** Where a hold lives, so release/confirm can find its pool. */
    private final Map<String, PoolKey> holdLocations = new ConcurrentHashMap<>();

    /** Confirmed holds, kept so confirm() is distinguishable from a lapsed hold. */
    private final Map<String, List<Long>> confirmed = new ConcurrentHashMap<>();

    /** Provisions a pool. Berth ids are derived so tests can predict them. */
    public PoolKey provision(PoolKey key, int berthCount, int segmentCount) {
        var berthIds = new ArrayList<Long>(berthCount);
        for (int ordinal = 0; ordinal < berthCount; ordinal++) {
            berthIds.add(key.scheduleId() * 1000L + ordinal);
        }
        pools.put(
                key,
                new PoolState(
                        new BerthPool(berthCount, segmentCount), List.copyOf(berthIds), new Object()));
        return key;
    }

    public List<Long> berthIdsOf(PoolKey key) {
        return state(key).berthIds();
    }

    /** Exposed so tests can assert INV-12 directly against the underlying pool. */
    public void checkInvariants(PoolKey key) {
        var s = state(key);
        synchronized (s.lock()) {
            s.pool().checkInvariants();
        }
    }

    @Override
    public AllocationResult allocate(AllocationRequest request) {
        var s = state(request.pool());

        synchronized (s.lock()) {
            var result =
                    s.pool()
                            .allocate(
                                    request.range(),
                                    request.passengerCount(),
                                    request.holdId(),
                                    request.now(),
                                    request.ttlMillis());

            if (result instanceof Allocated a) {
                holdLocations.put(request.holdId(), request.pool());
                return new AllocationResult.Allocated(
                        a.holdId(),
                        toBerthIds(s, a.berthOrdinals()),
                        a.range(),
                        request.now().plusMillis(request.ttlMillis()));
            }

            var unavailable = (io.tatkalrush.domain.inventory.AllocationResult.Unavailable) result;
            return new AllocationResult.Unavailable(
                    unavailable.available(), unavailable.requested());
        }
    }

    @Override
    public void release(String holdId) {
        PoolKey key = holdLocations.remove(holdId);
        if (key == null) {
            return; // already gone: expected, not an error
        }
        var s = state(key);
        synchronized (s.lock()) {
            s.pool().release(holdId);
        }
    }

    @Override
    public ConfirmResult confirm(String holdId, long bookingId) {
        PoolKey key = holdLocations.get(holdId);
        if (key == null) {
            return new ConfirmResult.HoldExpired(holdId);
        }
        var s = state(key);
        synchronized (s.lock()) {
            List<Long> berthIds = toBerthIds(s, s.pool().berthsOf(holdId));

            // confirm() keeps the berths and stops the expiry clock. If it
            // returns false the hold had already lapsed, which FR-24 treats as
            // benign - the caller auto-refunds with reason HOLD_EXPIRED.
            if (!s.pool().confirm(holdId)) {
                return new ConfirmResult.HoldExpired(holdId);
            }
            holdLocations.remove(holdId);
            confirmed.put(holdId, berthIds);
            return new ConfirmResult.Confirmed(bookingId, berthIds);
        }
    }

    @Override
    public AvailabilitySnapshot availability(PoolKey pool, SegmentRange range) {
        var s = state(pool);
        synchronized (s.lock()) {
            // Never stale: this fake computes exactly. A real implementation
            // serves from a 2 s cache (FR-15) and reports stale=true.
            return new AvailabilitySnapshot(pool, range, s.pool().freeOn(range), false);
        }
    }

    private PoolState state(PoolKey key) {
        PoolState s = pools.get(key);
        if (s == null) {
            throw new IllegalArgumentException("pool not provisioned: " + key);
        }
        return s;
    }

    private static List<Long> toBerthIds(PoolState s, List<Integer> ordinals) {
        var ids = new ArrayList<Long>(ordinals.size());
        for (int ordinal : ordinals) {
            ids.add(s.berthIds().get(ordinal));
        }
        return List.copyOf(ids);
    }

    /** Convenience for use-case tests that do not care about pool identity. */
    public Map<PoolKey, Integer> provisionedPools() {
        var view = new HashMap<PoolKey, Integer>();
        pools.forEach((k, v) -> view.put(k, v.pool().berthCount()));
        return Map.copyOf(view);
    }
}
