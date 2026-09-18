package io.tatkalrush.ops.warmup;

import io.tatkalrush.adapters.allocatorredis.RedisSeatAllocator;
import io.tatkalrush.ops.warmup.PoolShapeReader.PoolShape;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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
     * @param pools pools provisioned. Fewer than exist when a pool was refused
     *     for a shape problem serious enough to make its ids unusable.
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

    private final Connection connection;
    private final RedisSeatAllocator allocator;

    public PoolRebuilder(Connection connection, RedisSeatAllocator allocator) {
        this.connection = connection;
        this.allocator = allocator;
    }

    public Result rebuildAll() throws SQLException {
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

            List<Long> ids = berthIdsByPool.get(shape.poolId());
            if (ids == null) {
                // The pool's ordinals do not cover 0..berthCount-1, so some mask
                // slot maps to no berth. Refused rather than provisioned with a
                // partial mapping: a pool the allocator can allocate from and
                // cannot fully name writes the wrong berth id, which is exactly
                // the failure this whole mapping exists to remove. An unprovisioned
                // pool is loud on the first request; a wrong mapping is silent.
                warnings.add(
                        ("pool=%s: pool_berths ordinals do not cover 0..%d - NOT provisioned")
                                .formatted(shape.key(), shape.berthCount() - 1));
                continue;
            }

            allocator.provision(
                    shape.key(), shape.berthCount(), shape.segmentCount(), ids, occupied);
            berths += shape.berthCount();
            provisioned++;
        }

        return new Result(
                provisioned,
                berths,
                replayed[0],
                List.copyOf(warnings),
                System.currentTimeMillis() - started);
    }
}
