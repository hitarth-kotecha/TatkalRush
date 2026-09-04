package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;

/**
 * Approximate availability for a range (FR-13, FR-14).
 *
 * <p><b>Approximate is a design decision, not a limitation.</b> Search runs at
 * roughly nine times the rate of booking (§19's P2 is 90% search), so serving it
 * from an exact read would put the search load onto the same contended structure
 * the allocator needs. It is cached for 2 s (FR-15) and may be stale by that much.
 *
 * <p>A caller must never treat this as a reservation. Only
 * {@link SeatAllocator#allocate} decides, and a search that said "4 available"
 * followed by an allocation that fails is correct behaviour during a spike — not
 * a bug to be engineered away.
 *
 * @param pool which pool this describes
 * @param range the range asked about
 * @param freeBerths berths free across every segment of the range — the minimum,
 *     not the average, since a journey needs the same berth for its whole length
 * @param stale whether this came from cache rather than being computed now
 */
public record AvailabilitySnapshot(
        PoolKey pool, SegmentRange range, int freeBerths, boolean stale) {

    public AvailabilitySnapshot {
        if (freeBerths < 0) {
            throw new IllegalArgumentException("freeBerths cannot be negative, got " + freeBerths);
        }
    }

    public boolean hasSpace() {
        return freeBerths > 0;
    }
}
