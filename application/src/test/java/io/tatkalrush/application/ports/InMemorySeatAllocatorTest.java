package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TravelClass;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Validates the contract suite against a reference implementation.
 *
 * <p>The suite's purpose is to be inherited by Strategy A and Strategy B
 * unchanged (AC-1.6, AC-2.1). Running it here first means the contract is known
 * to be coherent and satisfiable before either real strategy exists — otherwise
 * the first strategy to fail it could not tell whether the bug was in the
 * implementation or in the expectations.
 */
class InMemorySeatAllocatorTest extends SeatAllocatorContract {

    private final InMemorySeatAllocator allocator = new InMemorySeatAllocator();

    /** Distinct per pool, so tests cannot collide through shared state. */
    private final AtomicLong nextScheduleId = new AtomicLong(1);

    @Override
    protected SeatAllocator allocator() {
        return allocator;
    }

    @Override
    protected PoolKey givenPool(int berthCount, int segmentCount) {
        var key =
                new PoolKey(
                        nextScheduleId.getAndIncrement(), TravelClass.SL, QuotaType.TATKAL);
        return allocator.provision(key, berthCount, segmentCount);
    }

    @Override
    protected List<Long> berthIdsOf(PoolKey pool) {
        return allocator.berthIdsOf(pool);
    }
}
