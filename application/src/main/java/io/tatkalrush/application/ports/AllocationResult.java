package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.SegmentRange;
import java.util.List;

/**
 * Outcome of an allocation attempt at the port boundary.
 *
 * <p>Distinct from {@code domain.inventory.AllocationResult}, and deliberately so.
 * The domain speaks in <b>pool ordinals</b> — bit positions within one pool's mask
 * array, meaningful only to the allocator. This speaks in <b>berth ids</b>, which
 * are what the booking layer, the {@code seat_allocations} table and a passenger's
 * ticket actually refer to. Letting bit positions leak upward would tie the
 * booking layer to a representation that exists for the allocator's convenience.
 */
public sealed interface AllocationResult {

    default boolean isAllocated() {
        return this instanceof Allocated;
    }

    /**
     * Berths were held.
     *
     * @param holdId echoes the request's hold id
     * @param berthIds database berth ids, ascending. Ascending because FR-5
     *     allocates lowest-ordinal-first and T-7 asserts the two implementations
     *     chose the <em>same</em> berths, not merely an equally valid set.
     * @param range the segment range now occupied on each berth
     * @param expiresAt when the hold lapses if payment does not complete. Durable
     *     in {@code bookings.hold_expires_at} rather than only in Redis, because
     *     chaos C2 flushes Redis during live payments and FR-24's "was the hold
     *     still valid?" decision must survive that.
     */
    record Allocated(
            String holdId, List<Long> berthIds, SegmentRange range, java.time.Instant expiresAt)
            implements AllocationResult {

        public Allocated {
            berthIds = List.copyOf(berthIds);
        }

        public int berthCount() {
            return berthIds.size();
        }
    }

    /**
     * Not enough berths were free for the whole request.
     *
     * <p>A normal outcome, not an error — FR-51 excludes it from the error budget
     * precisely because a Tatkal spike produces it by the thousand.
     *
     * @param available berths that were free, always fewer than requested
     * @param requested passengers on the booking
     */
    record Unavailable(int available, int requested) implements AllocationResult {}

    /**
     * The pool is a TATKAL pool whose window has not opened (FR-10, FR-28).
     *
     * <p>Separate from {@link Unavailable} because the two mean opposite things to
     * a caller: unavailable means "try another train", locked means "try again at
     * 10:00". Collapsing them would also corrupt the error taxonomy §11.2 and the
     * dashboards depend on.
     */
    record QuotaLocked(java.time.Instant opensAt) implements AllocationResult {}
}
