package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.time.Instant;

/**
 * One allocation attempt (§9.1).
 *
 * @param pool the contended unit: {@code (schedule, class, quota)}
 * @param range the journey's half-open segment range
 * @param passengerCount berths required, all or nothing (FR-6)
 * @param holdId identity of the hold to create. <b>This is the same identity as
 *     the request's {@code Idempotency-Key}</b> (FR-19, DD-009), not a separate
 *     notion — one identity edge-to-owner. Strategy B dedups commands on it and
 *     re-publishes the cached reply for a duplicate; if a retry arrived with a
 *     fresh id instead, a client retrying at spike peak would allocate a
 *     <em>second</em> set of berths and orphan the first for a full TTL.
 * @param now the caller's clock reading, passed rather than read so allocation is
 *     a pure function of its inputs and FR-31's injected {@code Clock} has
 *     somewhere to inject to. It also lets the Tatkal window be tested without
 *     waiting for it.
 * @param ttlMillis hold lifetime (FR-16: 120 s)
 */
public record AllocationRequest(
        PoolKey pool,
        SegmentRange range,
        int passengerCount,
        String holdId,
        Instant now,
        long ttlMillis) {

    public AllocationRequest {
        if (pool == null || range == null || now == null) {
            throw new IllegalArgumentException("pool, range and now are required");
        }
        if (holdId == null || holdId.isBlank()) {
            throw new IllegalArgumentException("holdId is required");
        }
        // FR-20 caps a user at 3 concurrent holds and the bookings table caps a
        // booking at 6 passengers. Bounded here so a malformed request cannot
        // make an allocator scan a pool for a group that could never be seated.
        if (passengerCount < 1 || passengerCount > 6) {
            throw new IllegalArgumentException(
                    "passengerCount must be 1..6, got " + passengerCount);
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive, got " + ttlMillis);
        }
    }
}
