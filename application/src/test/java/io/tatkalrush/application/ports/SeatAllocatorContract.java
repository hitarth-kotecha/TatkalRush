package io.tatkalrush.application.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The allocator contract</b> (AC-1.6, AC-2.1).
 *
 * <p>Every test here is written against {@link SeatAllocator}, never against an
 * implementation. Strategy A and Strategy B each supply a subject and inherit the
 * lot. <b>AC-2.1 requires Strategy B to pass this suite with no test
 * modifications</b> — that constraint is the whole point. If the suite had to bend
 * to accommodate the second implementation, the two would not be interchangeable
 * and §9.4 would be comparing two different systems rather than two strategies for
 * the same one.
 *
 * <p>Shipped as a Maven test-jar so both adapters extend the same <em>compiled</em>
 * class. Copying it into each adapter would let the two copies drift, and a
 * drifted "identical" suite is worse than no suite because it still reports green.
 *
 * <p>Includes T-1, T-2 and T-3 from §18.1, which the SDD explicitly says are run
 * against both strategies. T-1 in particular is the test that most directly proves
 * the central claim, and §18.1 says it should be the first test written.
 */
public abstract class SeatAllocatorContract {

    protected static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    protected static final long TTL = 120_000; // FR-16

    /** The implementation under test. */
    protected abstract SeatAllocator allocator();

    /**
     * Provisions a pool and returns its key.
     *
     * <p>The one thing implementations genuinely differ on: Strategy A writes
     * Redis keys, Strategy B builds an in-memory mask array behind a partition
     * owner. Everything else in this suite is identical for both.
     */
    protected abstract PoolKey givenPool(int berthCount, int segmentCount);

    /** Berth ids the implementation assigned to a pool, in ordinal order. */
    protected abstract List<Long> berthIdsOf(PoolKey pool);

    // --------------------------------------------------------------- helpers

    protected AllocationRequest request(
            PoolKey pool, int from, int to, int passengers, String holdId) {
        return request(pool, from, to, passengers, holdId, T0);
    }

    protected AllocationRequest request(
            PoolKey pool, int from, int to, int passengers, String holdId, Instant now) {
        return new AllocationRequest(
                pool, SegmentRange.of(from, to), passengers, holdId, now, TTL);
    }

    protected AllocationResult.Allocated allocated(AllocationResult result) {
        return assertInstanceOf(
                AllocationResult.Allocated.class, result, () -> "expected success, got " + result);
    }

    // ------------------------------------------------------------ allocation

    @Test
    @DisplayName("allocates the requested berths and reports them in ordinal order")
    void allocatesBerths() {
        var pool = givenPool(4, 4);
        var result = allocated(allocator().allocate(request(pool, 0, 4, 2, "h1")));

        assertEquals(2, result.berthCount());
        assertEquals(berthIdsOf(pool).subList(0, 2), result.berthIds(), "FR-5: lowest first");
        assertEquals(SegmentRange.of(0, 4), result.range());
        assertEquals(T0.plusMillis(TTL), result.expiresAt());
    }

    @Test
    @DisplayName("T-3: complementary journeys share one berth")
    void complementaryJourneysShareABerth() {
        // The capability this project exists to demonstrate (§2.1). A single
        // berth sold twice on disjoint legs. If an implementation fails this, it
        // has reduced segment-wise inventory to a seat counter.
        var pool = givenPool(1, 4);

        var first = allocated(allocator().allocate(request(pool, 0, 2, 1, "a")));
        var second = allocated(allocator().allocate(request(pool, 2, 4, 1, "b")));

        assertEquals(
                first.berthIds(),
                second.berthIds(),
                "both journeys must land on the same, only, berth");
    }

    @Test
    @DisplayName("T-2: journeys sharing an interior segment cannot share a berth")
    void interleavedJourneysConflict() {
        var pool = givenPool(1, 4);
        allocated(allocator().allocate(request(pool, 0, 2, 1, "a")));

        // [1,3) overlaps [0,2) on segment 1. One berth, so this must fail.
        var result = allocator().allocate(request(pool, 1, 3, 1, "b"));
        assertInstanceOf(AllocationResult.Unavailable.class, result);
    }

    @Test
    @DisplayName("FR-6: a group that does not fit allocates nothing at all")
    void groupAllocationIsAllOrNothing() {
        var pool = givenPool(3, 4);
        var result = allocator().allocate(request(pool, 0, 4, 5, "group"));

        var unavailable = assertInstanceOf(AllocationResult.Unavailable.class, result);
        assertEquals(5, unavailable.requested());

        // Nothing may have been consumed. A partial allocation would leave berths
        // held for a booking that failed - the orphaned hold §1 claims this
        // system does not produce.
        assertEquals(
                3,
                allocator().availability(pool, SegmentRange.of(0, 4)).freeBerths(),
                "a failed group booking must not consume berths");
    }

    @Test
    @DisplayName("an exhausted pool reports unavailable rather than failing")
    void exhaustedPoolIsUnavailableNotAnError() {
        var pool = givenPool(2, 2);
        allocated(allocator().allocate(request(pool, 0, 2, 2, "fills-it")));

        var result = allocator().allocate(request(pool, 0, 2, 1, "too-late"));
        var unavailable = assertInstanceOf(AllocationResult.Unavailable.class, result);
        assertEquals(0, unavailable.available());
    }

    // --------------------------------------------------------------- T-1

    @Test
    @DisplayName("T-1: 500 threads race for the last berth; exactly one wins")
    void lastBerthRace() throws Exception {
        // §18.1: "the test that most directly proves the central claim, and
        // should be the first test written."
        //
        // How each implementation makes this safe differs completely - Redis
        // serialises inside its own process, the single writer serialises by
        // partition ownership - and that difference is exactly what §9.4
        // measures. The contract cares only that the outcome is the same.
        final int threads = 500;
        var pool = givenPool(1, 4);
        var range = SegmentRange.of(0, 4);

        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(threads);

        var successes = new AtomicInteger();
        var unavailable = new AtomicInteger();
        var errors = new AtomicInteger();
        var firstError = new AtomicReference<Throwable>();
        var winningBerths = new AtomicReference<List<Long>>();

        for (int i = 0; i < threads; i++) {
            final int n = i;
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    ready.countDown();
                                    go.await();

                                    var result =
                                            allocator()
                                                    .allocate(request(pool, 0, 4, 1, "race-" + n));

                                    if (result instanceof AllocationResult.Allocated a) {
                                        successes.incrementAndGet();
                                        winningBerths.set(a.berthIds());
                                    } else if (result instanceof AllocationResult.Unavailable) {
                                        unavailable.incrementAndGet();
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                } catch (Throwable t) {
                                    errors.incrementAndGet();
                                    firstError.compareAndSet(null, t);
                                } finally {
                                    done.countDown();
                                }
                            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads failed to start");
        go.countDown(); // release them all at once
        assertTrue(done.await(60, TimeUnit.SECONDS), "the race did not finish in time");

        if (firstError.get() != null) {
            throw new AssertionError("an allocation threw during the race", firstError.get());
        }

        // The central claim, in three assertions.
        assertEquals(0, errors.get(), "no request may fail with an error");
        assertEquals(
                1,
                successes.get(),
                () ->
                        "EXACTLY ONE thread may win the last berth. "
                                + successes.get()
                                + " succeeded, which is overbooking.");
        assertEquals(threads - 1, unavailable.get(), "every loser must be told SEAT_UNAVAILABLE");

        assertEquals(1, winningBerths.get().size());
        assertEquals(
                0,
                allocator().availability(pool, range).freeBerths(),
                "the pool must now be empty");
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("release returns the berths and is idempotent")
    void releaseIsIdempotent() {
        var pool = givenPool(2, 4);
        var range = SegmentRange.of(0, 4);

        allocated(allocator().allocate(request(pool, 0, 4, 2, "h")));
        assertEquals(0, allocator().availability(pool, range).freeBerths());

        allocator().release("h");
        assertEquals(2, allocator().availability(pool, range).freeBerths());

        // Release arrives from expiry, cancellation and chart preparation, and
        // the lazy reaper can race a caller (§9.2). "Already gone" is expected.
        allocator().release("h");
        allocator().release("never-existed");
        assertEquals(2, allocator().availability(pool, range).freeBerths());
    }

    @Test
    @DisplayName("§9.2: an expired hold is reclaimed without a background reaper")
    void expiredHoldsAreReclaimedLazily() {
        // Correctness must not depend on the background reaper running. A stalled
        // reaper would otherwise lose seats permanently.
        var pool = givenPool(1, 4);
        allocated(allocator().allocate(request(pool, 0, 4, 1, "first")));

        assertInstanceOf(
                AllocationResult.Unavailable.class,
                allocator().allocate(request(pool, 0, 4, 1, "second")),
                "the hold is still live");

        var afterTtl = T0.plusMillis(TTL + 1);
        allocated(allocator().allocate(request(pool, 0, 4, 1, "third", afterTtl)));
    }

    @Test
    @DisplayName("confirm promotes a live hold")
    void confirmLiveHold() {
        var pool = givenPool(2, 4);
        var held = allocated(allocator().allocate(request(pool, 0, 4, 1, "h")));

        var result = allocator().confirm("h", 4242L);
        var confirmed = assertInstanceOf(ConfirmResult.Confirmed.class, result);

        assertEquals(4242L, confirmed.bookingId());
        assertEquals(held.berthIds(), confirmed.berthIds());
    }

    @Test
    @DisplayName("FR-24: confirming an expired hold is benign, not an error")
    void confirmExpiredHoldIsBenign() {
        // Payment succeeding after the hold lapsed is expected under chaos C2 and
        // C5. It must be distinguishable from an allocation conflict, which is a
        // bug - that separation is what stops a data bug becoming a money bug
        // while every invariant reports green (DD-008).
        var pool = givenPool(1, 4);
        allocated(allocator().allocate(request(pool, 0, 4, 1, "h")));
        allocator().release("h");

        assertInstanceOf(ConfirmResult.HoldExpired.class, allocator().confirm("h", 1L));
    }

    // ---------------------------------------------------------- availability

    @Test
    @DisplayName("availability reflects allocations, per segment")
    void availabilityTracksAllocations() {
        var pool = givenPool(4, 6);

        assertEquals(4, allocator().availability(pool, SegmentRange.of(0, 6)).freeBerths());

        allocated(allocator().allocate(request(pool, 2, 4, 2, "h")));

        assertEquals(
                4,
                allocator().availability(pool, SegmentRange.of(0, 2)).freeBerths(),
                "segments the booking does not touch are unaffected");
        assertEquals(
                2,
                allocator().availability(pool, SegmentRange.of(2, 4)).freeBerths(),
                "the occupied segments lose two berths");
        assertEquals(
                2,
                allocator().availability(pool, SegmentRange.of(0, 6)).freeBerths(),
                "a range spanning both reports the WORST segment, not an average -"
                        + " a journey needs the same berth for its whole length");
    }

    @Test
    @DisplayName("availability never claims more than exists")
    void availabilityIsBounded() {
        var pool = givenPool(3, 4);
        var snapshot = allocator().availability(pool, SegmentRange.of(0, 4));

        assertTrue(snapshot.freeBerths() <= 3);
        assertTrue(snapshot.hasSpace());
        assertEquals(pool, snapshot.pool());
    }
}
