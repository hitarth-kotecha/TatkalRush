package io.tatkalrush.ops.warmup;

import io.tatkalrush.adapters.allocatorredis.RedisSeatAllocator;
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
 * §13.4: rebuilds every pool's Redis state from Postgres.
 *
 * <h2>Why this has to exist at all</h2>
 *
 * <p>{@code RedisSeatAllocator.provision} had no production caller. Seed 345k
 * {@code pool_berths} rows, start the stack, and the first hold fails with "pool
 * not provisioned" while search reports a null count for every class — because
 * Postgres knows what berths exist and Redis, which is what the allocator actually
 * reads, knows nothing.
 *
 * <h2>One operation, two situations</h2>
 *
 * <p>Warming a freshly seeded system and recovering from chaos scenario C2's
 * {@code FLUSHALL} are the same operation, and {@code init-pool.lua} was already
 * written to serve both. Building them separately would produce two definitions of
 * "correct pool state" that agree until the day they do not.
 *
 * <h2>This does not share a line of SQL with INV-8, on purpose</h2>
 *
 * <p>INV-8 rebuilds expected masks from {@code seat_allocations} to <em>check</em>
 * Redis. This rebuilds masks from {@code seat_allocations} to <em>write</em> Redis.
 * Same question, opposite direction — and if they shared code, INV-8 would be
 * comparing Redis against a mask produced by the code that wrote it, which is a
 * tautology wearing an invariant's name.
 *
 * <p>{@code ops/invariant-checker}'s pom states the principle for the allocator:
 * the checker must be able to disagree. A writer has the opposite obligation, which
 * is why this module <em>does</em> depend on the allocator: {@code init-pool.lua}
 * owns the byte layout, and a second writer reproducing it would be a second
 * definition of it.
 *
 * <h2>Holds are not restored</h2>
 *
 * <p>Only confirmed allocations. In-flight holds are lost when Redis is, which §9.2
 * accepts and C2 tests — and hold <em>expiry</em> stays durable in
 * {@code bookings.hold_expires_at}, so FR-24's payment-side decision survives a
 * flush regardless.
 */
public final class PoolRebuilder {

    /**
     * @param pools pools provisioned
     * @param berths berth slots initialised across them
     * @param allocationsReplayed confirmed {@code seat_allocations} rows folded into
     *     masks. Zero on a freshly seeded system, non-zero after C2.
     * @param shapeWarnings pools whose {@code pool_berths} rows disagree with
     *     {@code quota_pools.total_berths} or are not contiguous from zero
     */
    public record Result(
            int pools,
            int berths,
            int allocationsReplayed,
            List<String> shapeWarnings,
            long elapsedMillis) {}

    /** One pool's shape, as Postgres has it. */
    private record PoolShape(
            long poolId, PoolKey key, int berthCount, int maxOrdinal, int declaredBerths, int segmentCount) {}

    private final Connection connection;
    private final RedisSeatAllocator allocator;

    public PoolRebuilder(Connection connection, RedisSeatAllocator allocator) {
        this.connection = connection;
        this.allocator = allocator;
    }

    public Result rebuildAll() throws SQLException {
        long started = System.currentTimeMillis();

        List<PoolShape> shapes = poolShapes();
        var replayed = new int[1];
        Map<Long, long[]> masksByPool = occupiedMasks(shapes, replayed);

        int berths = 0;
        var warnings = new ArrayList<String>();

        for (PoolShape shape : shapes) {
            warnings.addAll(shapeProblems(shape));

            long[] masks = masksByPool.get(shape.poolId());
            var occupied = new ArrayList<String>();
            if (masks != null) {
                for (int ordinal = 0; ordinal < masks.length; ordinal++) {
                    if (masks[ordinal] == 0) {
                        continue;
                    }
                    // Unsigned decimal halves. init-pool.lua matches on '(%d+)',
                    // so a negative would silently fail to parse and the berth
                    // would come back free - a berth somebody has paid for, resold.
                    occupied.add(
                            ordinal
                                    + ":"
                                    + Long.toUnsignedString(masks[ordinal] & 0xFFFF_FFFFL)
                                    + ":"
                                    + Long.toUnsignedString(masks[ordinal] >>> 32));
                }
            }

            allocator.provision(
                    shape.key(), shape.berthCount(), shape.segmentCount(), occupied);
            berths += shape.berthCount();
        }

        return new Result(
                shapes.size(),
                berths,
                replayed[0],
                List.copyOf(warnings),
                System.currentTimeMillis() - started);
    }

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
     * {@code availability} call fails its own length check inside Lua.
     */
    private List<PoolShape> poolShapes() throws SQLException {
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
     * Confirmed allocations, folded into one mask per berth.
     *
     * <p>No booking-status filter, and that is a fact about the schema rather than
     * an omission: {@code CancelBooking} deletes allocation rows in the same
     * transaction as the status change, precisely so this rebuild cannot
     * re-occupy a cancelled berth. Every surviving row is live.
     *
     * <p>Bits are OR-ed rather than assigned. T-3's complementary journeys put two
     * bookings on one berth over disjoint ranges, and assignment would keep
     * whichever the planner returned last.
     *
     * @param replayed out-parameter counting <em>rows</em> folded in. Counted here
     *     rather than at provisioning time because the caller sees only the
     *     resulting masks, and two complementary bookings on one berth produce one
     *     non-zero mask — so counting there would report "1 allocation replayed"
     *     for two, which is what {@code complementaryJourneysShareOneBerth} caught.
     */
    private Map<Long, long[]> occupiedMasks(List<PoolShape> shapes, int[] replayed)
            throws SQLException {
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
     * Reported rather than thrown.
     *
     * <p>A shape problem makes one pool wrong; refusing to provision the other
     * several thousand because of it would turn a data oddity into an outage. The
     * caller prints these and decides.
     */
    private static List<String> shapeProblems(PoolShape shape) {
        var problems = new ArrayList<String>(0);

        if (shape.maxOrdinal() != shape.berthCount() - 1) {
            // pool_ordinal is documented as 0-based and contiguous. A gap means
            // some mask slot maps to no berth, and an allocation landing there
            // would confirm a booking onto a berth that does not exist.
            //
            // The parentheses around the concatenation are load-bearing: .formatted
            // binds to the last literal alone, so without them the earlier fragment
            // is prepended to an already-formatted tail and the %s placeholders in
            // it survive into the message. That is exactly how this read on its
            // first run.
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
            // A long has 64 bits whatever the route's length (FR-3a, DD-002).
            problems.add(
                    "pool=%s: segmentCount=%d is outside 1..63"
                            .formatted(shape.key(), shape.segmentCount()));
        }
        return problems;
    }
}
