package io.tatkalrush.adapters.persistence;

import io.tatkalrush.application.ports.ScheduleQuery;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link ScheduleQuery} on Postgres.
 *
 * <p>One query per question, shaped around the caller rather than the tables. The
 * hold path needs the pool's size, the route's length, the journey date, the
 * departure instant and whether the chart is prepared — five facts spread over
 * three tables, and reconstructing that join at each call site is how a read model
 * becomes four different opinions about the same schedule.
 */
public final class JdbcScheduleQuery implements ScheduleQuery {

    private final JdbcClient jdbc;

    public JdbcScheduleQuery(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public Optional<PoolDescriptor> findPool(PoolKey pool) {
        return jdbc.sql(
                        """
                        SELECT q.total_berths,
                               s.journey_date,
                               s.departure_at,
                               s.status AS schedule_status,
                               (SELECT max(seq) FROM train_stops ts WHERE ts.train_id = s.train_id)
                                   AS segment_count
                        FROM quota_pools q
                        JOIN schedules s ON s.id = q.schedule_id
                        WHERE q.schedule_id = ? AND q.travel_class = ? AND q.quota_type = ?
                        """)
                .param(pool.scheduleId())
                .param(pool.travelClass().code())
                .param(pool.quotaType().name())
                .query((ResultSet rs, int rowNum) -> toDescriptor(pool, rs))
                .optional();
    }

    private static PoolDescriptor toDescriptor(PoolKey pool, ResultSet rs) throws SQLException {
        return new PoolDescriptor(
                pool,
                rs.getInt("total_berths"),
                // max(seq) IS the segment count: a route of N stops has segments
                // 0..N-2 and seq is 0-based. HoldSeats rejects toSeq beyond this,
                // and an off-by-one here would admit a booking that occupies a leg
                // the train does not run - which SegmentMask would set happily,
                // because a long has 64 bits whatever the route's length.
                rs.getInt("segment_count"),
                rs.getObject("journey_date", java.time.LocalDate.class),
                rs.getTimestamp("departure_at").toInstant(),
                "CHARTED".equals(rs.getString("schedule_status")));
    }

    @Override
    public BigDecimal distanceKm(long scheduleId, SegmentRange range) {
        // A SUBTRACTION, not a sum. train_stops.distance_km is cumulative from
        // the origin, so the distance covered by [from, to) is the difference
        // between the two stops' readings. There are no per-segment rows to add
        // up - a segment is what lies between two consecutive stops.
        //
        // BigDecimal all the way. The column is NUMERIC(7,2) and FR-67 feeds this
        // straight into a ceil; widening to double anywhere on this path is what
        // makes INV-7 report a pricing mismatch on values that look exact.
        return jdbc.sql(
                        """
                        SELECT to_stop.distance_km - from_stop.distance_km
                        FROM schedules s
                        JOIN train_stops from_stop
                          ON from_stop.train_id = s.train_id AND from_stop.seq = ?
                        JOIN train_stops to_stop
                          ON to_stop.train_id = s.train_id AND to_stop.seq = ?
                        WHERE s.id = ?
                        """)
                .param(range.fromSeq())
                .param(range.toSeq())
                .param(scheduleId)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(
                        () ->
                                // Either the schedule is gone or the route is
                                // shorter than the range. Both mean the caller
                                // skipped findPool's segment_count check, and
                                // returning zero would price the journey at the
                                // base fare rather than say so.
                                new IllegalArgumentException(
                                        "no stops at seq %d..%d for schedule %d"
                                                .formatted(
                                                        range.fromSeq(),
                                                        range.toSeq(),
                                                        scheduleId)));
    }

    /** Convenience for callers holding ids rather than a {@link PoolKey}. */
    public Optional<PoolDescriptor> findPool(
            long scheduleId, TravelClass travelClass, QuotaType quotaType) {
        return findPool(new PoolKey(scheduleId, travelClass, quotaType));
    }
}
