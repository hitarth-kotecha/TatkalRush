package io.tatkalrush.ops.warmup;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TravelClass;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What does Postgres say a pool's shape and occupancy are" — extracted from
 * {@link PoolRebuilder} (milestone 7) because Strategy B's provisioning needs
 * exactly the same three reads, and {@code occupiedMasks}'s output,
 * {@code Map<Long, long[]>} indexed by pool ordinal, is already the form
 * Strategy B's {@code provision(..., occupiedMasks)} takes directly — no
 * Lua-string packing in between, unlike {@link PoolRebuilder#rebuildAll}'s own
 * loop.
 *
 * <p>Sharing this is not the thing {@link PoolRebuilder}'s own class Javadoc
 * warns against. That warning is about a <b>checker</b> sharing code with the
 * <b>writer</b> it checks — INV-8 must derive its expected state independently
 * or agreement is a tautology. This is two <b>writers</b> reading the same
 * objective fact ("what does {@code pool_berths} say"); there is only one
 * correct answer to that question, and duplicating the query would just risk
 * the two copies drifting apart, not buy any independence worth having.
 */
final class PoolShapeReader {

    private PoolShapeReader() {}

    /** One pool's shape, as Postgres has it. */
    record PoolShape(
            long poolId, PoolKey key, int berthCount, int maxOrdinal, int declaredBerths, int segmentCount) {}

    /**
     * Berth count comes from {@code pool_berths}, not from
     * {@code quota_pools.total_berths}.
     *
     * <p>{@code pool_ordinal} is what the allocator addresses a mask slot by, so
     * the number of rows in {@code pool_berths} is the number of slots that can
     * exist. {@code total_berths} is a declaration; if the two disagree the
     * declaration is the one that is wrong, and provisioning to it would either
     * strand berths past the end of the mask array or create slots nothing maps to.
     *
     * <p>{@code max(seq)} is the segment count — a route of N stops has segments
     * {@code 0..N-2} and {@code seq} is 0-based. Wrong here and every
     * {@code availability} call fails its own length check.
     */
    static List<PoolShape> poolShapes(Connection connection) throws SQLException {
        String sql =
                """
                SELECT q.id            AS pool_id,
                       q.schedule_id,
                       q.travel_class,
                       q.quota_type,
                       q.total_berths,
                       count(pb.berth_id)     AS berth_count,
                       max(pb.pool_ordinal)   AS max_ordinal,
                       (SELECT max(seq) FROM train_stops ts WHERE ts.train_id = s.train_id)
                           AS segment_count
                FROM quota_pools q
                JOIN schedules s   ON s.id = q.schedule_id
                JOIN pool_berths pb ON pb.pool_id = q.id
                WHERE s.status IN ('OPEN', 'CHARTED')
                GROUP BY q.id, q.schedule_id, q.travel_class, q.quota_type,
                         q.total_berths, s.train_id
                ORDER BY q.id
                """;

        var shapes = new ArrayList<PoolShape>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                shapes.add(
                        new PoolShape(
                                rs.getLong("pool_id"),
                                new PoolKey(
                                        rs.getLong("schedule_id"),
                                        TravelClass.fromCode(rs.getString("travel_class")),
                                        QuotaType.fromCode(rs.getString("quota_type"))),
                                rs.getInt("berth_count"),
                                rs.getInt("max_ordinal"),
                                rs.getInt("total_berths"),
                                rs.getInt("segment_count")));
            }
        }
        return shapes;
    }

    /**
     * Confirmed allocations, folded into one mask per berth ordinal, indexed by
     * pool id.
     *
     * <p>No booking-status filter, and that is a fact about the schema rather
     * than an omission: {@code CancelBooking} deletes allocation rows in the
     * same transaction as the status change, precisely so this cannot re-occupy
     * a cancelled berth. Every surviving row is live.
     *
     * <p>Bits are OR-ed rather than assigned. T-3's complementary journeys put
     * two bookings on one berth over disjoint ranges, and assignment would keep
     * whichever the planner returned last.
     *
     * @param replayed out-parameter counting <em>rows</em> folded in
     */
    static Map<Long, long[]> occupiedMasks(
            Connection connection, List<PoolShape> shapes, int[] replayed) throws SQLException {
        var sizeByPool = new LinkedHashMap<Long, Integer>();
        for (PoolShape shape : shapes) {
            sizeByPool.put(shape.poolId(), shape.berthCount());
        }

        String sql =
                """
                SELECT q.id              AS pool_id,
                       pb.pool_ordinal,
                       lower(sa.seg_range) AS from_seq,
                       upper(sa.seg_range) AS to_seq
                FROM seat_allocations sa
                JOIN bookings b     ON b.id = sa.booking_id
                JOIN quota_pools q  ON q.schedule_id  = sa.schedule_id
                                   AND q.travel_class = b.travel_class
                                   AND q.quota_type   = b.quota_type
                JOIN pool_berths pb ON pb.pool_id = q.id AND pb.berth_id = sa.berth_id
                """;

        var masks = new LinkedHashMap<Long, long[]>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long poolId = rs.getLong("pool_id");
                Integer size = sizeByPool.get(poolId);
                if (size == null) {
                    // A pool on a DEPARTED or CANCELLED schedule. Not provisioned,
                    // so there is no mask array to write into.
                    continue;
                }
                int ordinal = rs.getInt("pool_ordinal");
                if (ordinal < 0 || ordinal >= size) {
                    continue;
                }
                long[] pool = masks.computeIfAbsent(poolId, id -> new long[size]);
                for (int seg = rs.getInt("from_seq"); seg < rs.getInt("to_seq"); seg++) {
                    pool[ordinal] |= 1L << seg;
                }
                replayed[0]++;
            }
        }
        return masks;
    }

    /**
     * Each pool's {@code pool_ordinal -> berths.id} mapping.
     *
     * <p><b>Each id is placed at its own ordinal</b> rather than appended in
     * query order — see {@link PoolRebuilder}'s original Javadoc (now here)
     * for why an unfilled slot must be detectable rather than silently absorbed
     * by append order.
     *
     * @return ids by ordinal, or an entry absent entirely when the pool's
     *     ordinals do not cover {@code 0..berthCount-1}
     */
    static Map<Long, List<Long>> berthIds(Connection connection, List<PoolShape> shapes)
            throws SQLException {
        var sizes = new LinkedHashMap<Long, Integer>();
        for (PoolShape shape : shapes) {
            sizes.put(shape.poolId(), shape.berthCount());
        }

        String sql =
                """
                SELECT pb.pool_id, pb.pool_ordinal, pb.berth_id
                FROM pool_berths pb
                JOIN quota_pools q ON q.id = pb.pool_id
                JOIN schedules s   ON s.id = q.schedule_id
                WHERE s.status IN ('OPEN', 'CHARTED')
                ORDER BY pb.pool_id, pb.berth_id
                """;

        // Ordered by BERTH ID, deliberately not by pool_ordinal - see
        // PoolRebuilder's original Javadoc: this loop's independence from
        // arrival order is what the test suite proves.
        //
        // 0 is a legal berth id in principle, so an unfilled slot needs a value
        // no real id can take. -1 works because berths.id is a BIGSERIAL.
        var byPool = new LinkedHashMap<Long, long[]>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long poolId = rs.getLong("pool_id");
                Integer size = sizes.get(poolId);
                if (size == null) {
                    continue;
                }
                int ordinal = rs.getInt("pool_ordinal");
                if (ordinal < 0 || ordinal >= size) {
                    continue;
                }
                long[] ids =
                        byPool.computeIfAbsent(
                                poolId,
                                id -> {
                                    var fresh = new long[size];
                                    java.util.Arrays.fill(fresh, -1L);
                                    return fresh;
                                });
                ids[ordinal] = rs.getLong("berth_id");
            }
        }

        var complete = new LinkedHashMap<Long, List<Long>>();
        for (var entry : byPool.entrySet()) {
            long[] ids = entry.getValue();
            boolean full = true;
            for (long id : ids) {
                if (id < 0) {
                    full = false;
                    break;
                }
            }
            if (full) {
                var boxed = new ArrayList<Long>(ids.length);
                for (long id : ids) {
                    boxed.add(id);
                }
                complete.put(entry.getKey(), List.copyOf(boxed));
            }
        }
        return complete;
    }

    /**
     * Reported rather than thrown — a shape problem makes one pool wrong;
     * refusing to provision the other several thousand because of it would
     * turn a data oddity into an outage. The caller prints these and decides.
     */
    static List<String> shapeProblems(PoolShape shape) {
        var problems = new ArrayList<String>(0);

        if (shape.maxOrdinal() != shape.berthCount() - 1) {
            problems.add(
                    ("pool=%s: %d pool_berths rows but max(pool_ordinal)=%d - ordinals are"
                                    + " not contiguous from zero")
                            .formatted(shape.key(), shape.berthCount(), shape.maxOrdinal()));
        }
        if (shape.declaredBerths() != shape.berthCount()) {
            problems.add(
                    ("pool=%s: quota_pools.total_berths=%d but %d pool_berths rows -"
                                    + " provisioning to the rows")
                            .formatted(
                                    shape.key(), shape.declaredBerths(), shape.berthCount()));
        }
        if (shape.segmentCount() <= 0 || shape.segmentCount() > 63) {
            problems.add(
                    "pool=%s: segmentCount=%d is outside 1..63"
                            .formatted(shape.key(), shape.segmentCount()));
        }
        return problems;
    }
}
