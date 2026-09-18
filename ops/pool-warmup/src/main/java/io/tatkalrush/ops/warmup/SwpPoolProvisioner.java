package io.tatkalrush.ops.warmup;

import io.tatkalrush.adapters.allocatorswp.KafkaSeatAllocator;
import io.tatkalrush.ops.warmup.PoolShapeReader.PoolShape;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Strategy B's provisioning path (§9.3, milestone 7): every {@code
 * KafkaSeatAllocator.provision} call in this codebase reaches this class.
 *
 * <h2>Why this exists, and why it is smaller than {@link PoolRebuilder}</h2>
 *
 * <p>{@code KafkaSeatAllocator.provision} had no production caller for exactly
 * the reason {@code RedisSeatAllocator.provision} did not, before
 * {@link PoolRebuilder} existed: seed the database, start the stack, and the
 * first {@code allocate} throws "pool not provisioned on this owner" while
 * search reports nothing for every class.
 *
 * <p>It is a smaller class than {@link PoolRebuilder} because it solves a
 * smaller problem. Strategy A's Redis state is <b>volatile</b> — chaos
 * scenario C2 {@code FLUSHALL}s it, so §13.4's rebuild has to serve both
 * "warm a freshly seeded system" and "recover after a flush", and those are
 * the same operation for a reason {@link PoolRebuilder}'s own Javadoc explains.
 * Strategy B's state is <b>durable by construction</b> once a partition is
 * provisioned: an owner that dies recovers by replaying {@code booking-events}
 * (milestone 3), never by re-reading Postgres. So this class only ever needs
 * to run <b>once</b>, at seed time, before the first booking — there is no
 * chaos-recovery use for it, and adding one would be solving a problem
 * replay already solves.
 *
 * <h2>Occupied masks are still folded in, for one reason</h2>
 *
 * <p>Provisioning a pool that already has confirmed bookings — this tool run
 * twice by mistake, or run against a partially-booked dataset — must not
 * silently reopen berths someone has paid for. {@code
 * PartitionOwner.provision}'s five-argument overload (milestone 7) exists for
 * exactly this, and {@link PoolShapeReader#occupiedMasks} already returns
 * precisely the {@code Map<Long, long[]>} shape that call needs, indexed by
 * ordinal — no repacking, unlike {@link PoolRebuilder}'s Lua-string encoding.
 *
 * <h2>One round trip per pool, and why that is acceptable here</h2>
 *
 * <p>{@code KafkaSeatAllocator.provision} blocks on a real Kafka round trip
 * (§9.3's request/reply protocol) — there is no Redis-style single Lua call
 * that provisions a pool locally. At seed-time data volumes (thousands of
 * pools, run once, not on any request path) a few tens of milliseconds each
 * is minutes, not the kind of cost this project measures as a benchmark
 * result. Pipelining many pools' provision commands concurrently would cut
 * that further; not built, because nothing yet needs seed time to be faster
 * than it already is.
 */
public final class SwpPoolProvisioner {

    /**
     * @param pools pools provisioned. Fewer than exist when a pool was refused
     *     for a shape problem serious enough to make its ids unusable.
     * @param berths berth slots provisioned across them
     * @param allocationsReplayed confirmed {@code seat_allocations} rows folded
     *     into the pools' starting occupancy. Zero on a freshly seeded system.
     * @param shapeWarnings pools whose {@code pool_berths} rows disagree with
     *     {@code quota_pools.total_berths} or are not contiguous from zero
     */
    public record Result(
            int pools,
            int berths,
            int allocationsReplayed,
            List<String> shapeWarnings,
            long elapsedMillis) {}

    private final Connection connection;
    private final KafkaSeatAllocator allocator;

    /** {@code allocator} must already be {@code start()}ed — this class only provisions. */
    public SwpPoolProvisioner(Connection connection, KafkaSeatAllocator allocator) {
        this.connection = connection;
        this.allocator = allocator;
    }

    public Result provisionAll() throws SQLException {
        long started = System.currentTimeMillis();

        List<PoolShape> shapes = PoolShapeReader.poolShapes(connection);
        var replayed = new int[1];
        Map<Long, long[]> masksByPool = PoolShapeReader.occupiedMasks(connection, shapes, replayed);
        Map<Long, List<Long>> berthIdsByPool = PoolShapeReader.berthIds(connection, shapes);

        int berths = 0;
        int provisioned = 0;
        var warnings = new ArrayList<String>();

        for (PoolShape shape : shapes) {
            warnings.addAll(PoolShapeReader.shapeProblems(shape));

            List<Long> ids = berthIdsByPool.get(shape.poolId());
            if (ids == null) {
                // Same refusal PoolRebuilder makes, for the same reason: a pool
                // the allocator can allocate from and cannot fully name would
                // record the wrong berth id silently.
                warnings.add(
                        ("pool=%s: pool_berths ordinals do not cover 0..%d - NOT provisioned")
                                .formatted(shape.key(), shape.berthCount() - 1));
                continue;
            }

            long[] masks = masksByPool.get(shape.poolId());
            List<Long> occupied = masks == null ? List.of() : boxed(masks);

            allocator.provision(shape.key(), shape.berthCount(), shape.segmentCount(), ids, occupied);
            berths += shape.berthCount();
            provisioned++;
        }

        return new Result(
                provisioned, berths, replayed[0], List.copyOf(warnings),
                System.currentTimeMillis() - started);
    }

    private static List<Long> boxed(long[] masks) {
        var list = new ArrayList<Long>(masks.length);
        for (long mask : masks) {
            list.add(mask);
        }
        return list;
    }
}
