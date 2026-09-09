package io.tatkalrush.adapters.persistence;

import io.tatkalrush.application.ports.TrainSearchQuery;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link TrainSearchQuery} on Postgres.
 *
 * <p>One query, joined seven ways, returning one row per bookable pool. The
 * alternative — find trains, then for each train resolve the range, then for each
 * train list its pools — is three round trips multiplied by the number of trains
 * on the route, on the endpoint that carries nine tenths of P2's load.
 */
public final class JdbcTrainSearchQuery implements TrainSearchQuery {

    /**
     * The route join, and the two things about it that are easy to get wrong.
     *
     * <p><b>{@code from_stop.seq < to_stop.seq} is the direction filter.</b> Both
     * stations being on the route is not enough: a passenger cannot travel Mumbai
     * to Delhi on a train running Delhi to Mumbai. Without this the query returns
     * the reversed journey too, and its range would be negative — which
     * {@code SegmentRange} rejects, so the failure would arrive as an exception
     * from a search rather than as an absent result.
     *
     * <p><b>{@code max(seq)} is the segment count</b>, not {@code count(*)}. A
     * route of N stops has segments {@code 0..N-2} and {@code seq} is 0-based. The
     * allocator uses this to read a free-count blob whose length it checks, so an
     * off-by-one here surfaces as "segment count mismatch" from Lua rather than as
     * a wrong number.
     *
     * <p>{@code status IN ('OPEN','CHARTED')} rather than {@code = 'OPEN'}: a
     * charted train still exists and a passenger searching for it deserves to be
     * told it is charted (FR-42) rather than that it does not run. Departed and
     * cancelled schedules are genuinely not answers to the question.
     */
    private static final String BASE_SQL =
            """
            SELECT t.number        AS train_number,
                   t.name          AS train_name,
                   t.is_hot,
                   s.id            AS schedule_id,
                   s.journey_date,
                   s.departure_at,
                   s.status        AS schedule_status,
                   from_stop.seq   AS from_seq,
                   to_stop.seq     AS to_seq,
                   from_stop.dep_time,
                   to_stop.arr_time,
                   (to_stop.distance_km - from_stop.distance_km) AS distance_km,
                   q.travel_class,
                   q.quota_type,
                   q.total_berths,
                   (SELECT max(seq) FROM train_stops m WHERE m.train_id = t.id)
                       AS segment_count
            FROM schedules s
            JOIN trains t              ON t.id = s.train_id
            JOIN train_stops from_stop ON from_stop.train_id = t.id
            JOIN stations from_station ON from_station.id = from_stop.station_id
                                      AND from_station.code = ?
            JOIN train_stops to_stop   ON to_stop.train_id = t.id
            JOIN stations to_station   ON to_station.id = to_stop.station_id
                                      AND to_station.code = ?
            JOIN quota_pools q         ON q.schedule_id = s.id
            WHERE s.journey_date = ?
              AND s.status IN ('OPEN', 'CHARTED')
              AND from_stop.seq < to_stop.seq
            """;

    /**
     * Stable ordering, and it is not cosmetic: two benchmark runs of the same
     * profile should differ in their numbers and nowhere else, and a response whose
     * trains arrive in whatever order the planner chose makes every diff useless.
     */
    private static final String ORDER_BY = " ORDER BY t.number, q.travel_class, q.quota_type";

    private final JdbcClient jdbc;

    public JdbcTrainSearchQuery(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public List<PoolOnRoute> poolsOnRoute(Query query) {
        // The class filter is appended rather than bound as a nullable parameter.
        // Postgres cannot infer the type of a bare `?` in `? IS NULL`, so the
        // single-statement form needs a CAST and the same value bound twice - two
        // opportunities for a positional mistake, to avoid one string concatenation
        // over a constant.
        String sql =
                query.travelClass().isPresent()
                        ? BASE_SQL + "  AND q.travel_class = ?" + ORDER_BY
                        : BASE_SQL + ORDER_BY;

        var statement =
                jdbc.sql(sql)
                        .param(query.fromStationCode())
                        .param(query.toStationCode())
                        .param(query.journeyDate());

        if (query.travelClass().isPresent()) {
            statement = statement.param(query.travelClass().get().code());
        }

        return statement.query((ResultSet rs, int rowNum) -> toPoolOnRoute(rs)).list();
    }

    private static PoolOnRoute toPoolOnRoute(ResultSet rs) throws SQLException {
        var pool =
                new PoolKey(
                        rs.getLong("schedule_id"),
                        TravelClass.fromCode(rs.getString("travel_class")),
                        QuotaType.valueOf(rs.getString("quota_type")));

        return new PoolOnRoute(
                pool,
                rs.getString("train_number"),
                rs.getString("train_name"),
                rs.getBoolean("is_hot"),
                new SegmentRange(rs.getInt("from_seq"), rs.getInt("to_seq")),
                rs.getInt("segment_count"),
                rs.getInt("total_berths"),
                rs.getObject("journey_date", java.time.LocalDate.class),
                rs.getTimestamp("departure_at").toInstant(),
                // Null at a train's own origin and terminus - there is no arrival
                // at the first stop and no departure from the last. Read as objects
                // so those stay null instead of becoming midnight.
                rs.getObject("dep_time", LocalTime.class),
                rs.getObject("arr_time", LocalTime.class),
                rs.getBigDecimal("distance_km"),
                "CHARTED".equals(rs.getString("schedule_status")));
    }

    @Override
    public List<String> stationsExist(List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }

        // Placeholders expanded rather than a bound array, as elsewhere in this
        // module: JdbcClient has no portable SQL-array binding and this is called
        // with two codes.
        String placeholders = String.join(", ", Collections.nCopies(codes.size(), "?"));

        var statement =
                jdbc.sql("SELECT code FROM stations WHERE code IN (%s)".formatted(placeholders));
        for (String code : codes) {
            statement = statement.param(code);
        }

        return statement.query(String.class).list();
    }
}
