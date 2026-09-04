package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;

/**
 * The port at the centre of this project (§9.1).
 *
 * <p>Two implementations exist and are selected by
 * {@code tatkal.allocator.strategy = redis-lua | single-writer}:
 *
 * <ul>
 *   <li><b>Strategy A</b> — a Lua script executing inside Redis, which is
 *       single-threaded, so the read-modify-write cannot interleave (§9.2).
 *   <li><b>Strategy B</b> — a Kafka-partitioned single writer that calls
 *       {@code domain/inventory} directly on one consumer thread (§9.3).
 * </ul>
 *
 * <p><b>Both must pass the identical contract suite with no test modifications</b>
 * (AC-1.6, AC-2.1). That constraint is what makes §9.4 a controlled comparison: if
 * the suite had to bend to accommodate the second implementation, the two would
 * not be interchangeable and the benchmark would be comparing two different
 * systems rather than two strategies for the same one.
 *
 * <h2>Thread safety is the implementation's problem, not the caller's</h2>
 *
 * <p>Callers may invoke these methods concurrently from many virtual threads. How
 * that is made safe differs completely between the strategies — Redis serialises
 * inside its own process; the single writer serialises by partition ownership —
 * and that difference is precisely what §9.4 measures. The interface promises the
 * outcome, not the mechanism.
 */
public interface SeatAllocator {

    /**
     * Allocates berths and creates a time-limited hold, all or nothing (FR-6).
     *
     * <p>Unavailability is a normal outcome, not an error: during a Tatkal spike
     * most requests fail this way, which is why FR-51 excludes
     * {@code SEAT_UNAVAILABLE} from the error budget.
     */
    AllocationResult allocate(AllocationRequest request);

    /**
     * Releases a hold's berths.
     *
     * <p>Idempotent, and returns quietly for a hold that is already gone. Release
     * arrives from expiry, cancellation and chart preparation, and the lazy reaper
     * can race a caller for the same hold (§9.2) — so "already released" is an
     * expected state, not a failure.
     */
    void release(String holdId);

    /**
     * Promotes a live hold into a durable confirmed allocation.
     *
     * <p>Ordering is specified and load-bearing (FR-25, DD-008): the hold's
     * liveness is validated <b>before</b> the allocation rows are inserted. That
     * is what separates a benign expiry race (FR-24, expected during chaos C2)
     * from an allocator defect — without it, the two are indistinguishable, and
     * the most serious bug the system can have hides inside its most routine
     * event.
     */
    ConfirmResult confirm(String holdId, long bookingId);

    /**
     * Berths available for a range, without allocating (FR-13).
     *
     * <p>Approximate by design: it is served from a short-TTL cache (FR-15) and
     * may be stale. A caller must not treat it as a reservation — only
     * {@link #allocate} decides.
     */
    AvailabilitySnapshot availability(PoolKey pool, SegmentRange range);
}
