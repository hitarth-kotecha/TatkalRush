package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read model for everything the hold path needs to know about a schedule.
 *
 * <p>A query port rather than a repository: nothing here is mutated, and shaping
 * it around the caller's question rather than around the tables means one lookup
 * instead of four joins reconstructed at each call site.
 */
public interface ScheduleQuery {

    /**
     * Shape and timing of one quota pool.
     *
     * @param berthCount berths in this pool
     * @param segmentCount segments on the route, which the allocator needs to
     *     read its free-count blob correctly
     * @param journeyDate the date the train departs its origin. Drives FR-28's
     *     Tatkal window, which opens on D-1 by <em>calendar day</em> — so the
     *     date is needed, not merely the departure instant.
     * @param departureAt used by FR-44's refund tiers
     * @param chartPrepared once charted, booking is closed (FR-42, §11.2's
     *     {@code CHART_PREPARED})
     */
    record PoolDescriptor(
            PoolKey key,
            int berthCount,
            int segmentCount,
            LocalDate journeyDate,
            Instant departureAt,
            boolean chartPrepared) {}

    Optional<PoolDescriptor> findPool(PoolKey pool);

    /**
     * Distance covered by a segment range, summed over {@code train_stops}.
     *
     * <p>{@code BigDecimal} because the column is {@code NUMERIC(7,2)} and FR-67
     * feeds this straight into a {@code ceil}. Widening it to {@code double}
     * anywhere on this path reintroduces the rounding error INV-7 would report as
     * a pricing mismatch.
     */
    BigDecimal distanceKm(long scheduleId, SegmentRange range);

    /**
     * Translates the two station codes a caller knows into the segment range the
     * allocator needs.
     *
     * <p>§11.1's request carries {@code fromStationCode} and {@code toStationCode},
     * because that is what a passenger knows. Everything below this port speaks
     * segment indices, because that is what a 64-bit mask addresses. Doing the
     * translation here rather than in the controller keeps it a single query
     * instead of two lookups and an arithmetic mistake waiting to happen.
     *
     * <p>Empty when either station is not on this train's route, or when they are
     * in the wrong order — a passenger cannot travel Mumbai to Delhi on a train
     * running Delhi to Mumbai, and expressing that as a negative range would
     * produce an empty mask that conflicts with nothing and appears to succeed
     * against every berth.
     */
    Optional<SegmentRange> resolveRange(
            long scheduleId, String fromStationCode, String toStationCode);

    /**
     * Where a berth physically is, for §11.1's {@code allocations} block.
     *
     * @param berthId matches what the allocator returned
     * @param coachCode e.g. {@code "S1"}
     * @param ordinal the berth's number within its coach
     * @param berthType {@code LOWER}, {@code SIDE_UPPER} and so on
     */
    record BerthDetail(long berthId, String coachCode, int ordinal, String berthType) {}

    /** Ordered to match {@code berthIds}, so a response lists berths as allocated. */
    List<BerthDetail> describeBerths(List<Long> berthIds);
}
