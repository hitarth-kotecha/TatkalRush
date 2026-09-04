package io.tatkalrush.adapters.allocatorredis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.application.ports.AvailabilitySnapshot;
import io.tatkalrush.application.ports.ConfirmResult;
import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Strategy A</b> (§9.2): allocation executed atomically inside Redis as Lua.
 *
 * <p>This class does almost nothing, and that is the design. Redis runs Lua
 * single-threaded, so the entire read-modify-write happens without interleaving —
 * no lock, no CAS retry loop, no optimistic version check anywhere in this file.
 * Strategy A's concurrency control is a property of <em>where the algorithm
 * runs</em>, and the Java side is a transport that marshals arguments and decodes
 * a reply.
 *
 * <p>Expect the trade-off that follows from that (§9.2): one network round trip
 * per allocation, contention resolved inside Redis, and Redis becoming the
 * single-threaded bottleneck for the hottest partition. §9.4 says to predict this
 * before measuring it and then check the prediction — that comparison is worth
 * more than the raw numbers.
 *
 * <h2>Key layout</h2>
 *
 * <p>§10.5 specifies {@code masks:}, {@code holds:} and {@code freecount:} per
 * pool. Three more are needed by this implementation and are recorded in DD-031:
 *
 * <ul>
 *   <li>{@code holddetail:{pool}} — a HASH of holdId to its mask and berths. The
 *       reap loop inside {@code allocate.lua} needs each expired hold's detail,
 *       and a per-<em>hold</em> key would force the script to construct keys it
 *       had not declared.
 *   <li>{@code poolmeta:{pool}} — berth and segment counts, so the adapter can
 *       tell the scripts the pool's shape without a schema lookup per call.
 *   <li>{@code holdpool:{holdId}} — which pool a hold belongs to. The port's
 *       {@code release(holdId)} and {@code confirm(holdId)} carry no pool, and
 *       the mapping has to survive an app restart because any replica may serve
 *       the release for a hold another replica created.
 * </ul>
 */
public final class RedisSeatAllocator implements SeatAllocator {

    private final RedisCommands<String, String> redis;
    private final LuaScripts scripts;

    /**
     * Pool shape, cached per JVM. A pool's berth and segment counts are fixed at
     * schedule creation, so this never needs invalidation — and reading them from
     * Redis on every allocation would double the round trips the strategy is
     * measured on.
     */
    private final ConcurrentHashMap<PoolKey, PoolShape> shapes = new ConcurrentHashMap<>();

    private record PoolShape(int berthCount, int segmentCount) {}

    public RedisSeatAllocator(RedisCommands<String, String> redis) {
        this.redis = redis;
        this.scripts = new LuaScripts(redis);
    }

    // ------------------------------------------------------------ provisioning

    /**
     * Creates or rebuilds a pool's Redis state (§9.2, §13.4).
     *
     * @param occupied confirmed allocations to replay in, as
     *     {@code "ordinal:maskLo:maskHi"}. Used by §13.4's rebuild after chaos C2
     *     flushes Redis; empty at schedule creation.
     */
    public void provision(
            PoolKey pool, int berthCount, int segmentCount, List<String> occupied) {
        var args = new ArrayList<String>(2 + occupied.size());
        args.add(String.valueOf(berthCount));
        args.add(String.valueOf(segmentCount));
        args.addAll(occupied);

        scripts.run(
                "init-pool",
                ScriptOutputType.INTEGER,
                poolKeys(pool),
                args.toArray(String[]::new));

        redis.hset(metaKey(pool), "berths", String.valueOf(berthCount));
        redis.hset(metaKey(pool), "segments", String.valueOf(segmentCount));
        shapes.put(pool, new PoolShape(berthCount, segmentCount));
    }

    // -------------------------------------------------------------- allocate

    @Override
    public AllocationResult allocate(AllocationRequest request) {
        PoolShape shape = shapeOf(request.pool());
        long mask = request.range().mask();

        List<Object> reply =
                scripts.run(
                        "allocate",
                        ScriptOutputType.MULTI,
                        poolKeys(request.pool()),
                        // The mask crosses as two unsigned 32-bit halves. Lua 5.1
                        // has no 64-bit integer, so handing it one number would be
                        // silently rounded above 2^53 - a wrong availability
                        // answer with no error raised (FR-3a).
                        Long.toUnsignedString(mask & 0xFFFF_FFFFL),
                        Long.toUnsignedString(mask >>> 32),
                        String.valueOf(request.passengerCount()),
                        request.holdId(),
                        String.valueOf(request.ttlMillis()),
                        String.valueOf(request.now().toEpochMilli()),
                        String.valueOf(shape.segmentCount()));

        String status = String.valueOf(reply.get(0));
        if (!"OK".equals(status)) {
            int available = reply.size() > 1 ? ((Long) reply.get(1)).intValue() : 0;
            return new AllocationResult.Unavailable(available, request.passengerCount());
        }

        var berthIds = new ArrayList<Long>(reply.size() - 1);
        for (int i = 1; i < reply.size(); i++) {
            berthIds.add(berthIdOf(request.pool(), ((Long) reply.get(i)).intValue()));
        }

        // Records where the hold lives so release/confirm can find its pool.
        // Outlives the hold by a margin: a release arriving just after expiry must
        // still resolve, or it silently becomes a no-op and the berths wait for
        // the lazy reaper instead.
        redis.psetex(
                holdPoolKey(request.holdId()),
                request.ttlMillis() + 60_000,
                request.pool().keySuffix());

        return new AllocationResult.Allocated(
                request.holdId(),
                List.copyOf(berthIds),
                request.range(),
                request.now().plusMillis(request.ttlMillis()));
    }

    // --------------------------------------------------------------- release

    @Override
    public void release(String holdId) {
        PoolKey pool = poolOfHold(holdId);
        if (pool == null) {
            return; // already gone: the expected concurrent outcome, not an error
        }
        scripts.run(
                "release",
                ScriptOutputType.INTEGER,
                poolKeys(pool),
                holdId,
                String.valueOf(shapeOf(pool).segmentCount()));
        redis.del(holdPoolKey(holdId));
    }

    // --------------------------------------------------------------- confirm

    @Override
    public ConfirmResult confirm(String holdId, long bookingId) {
        PoolKey pool = poolOfHold(holdId);
        if (pool == null) {
            return new ConfirmResult.HoldExpired(holdId);
        }

        List<Object> reply =
                scripts.run(
                        "confirm",
                        ScriptOutputType.MULTI,
                        new String[] {holdsKey(pool), detailKey(pool)},
                        holdId);

        redis.del(holdPoolKey(holdId));

        if (!"OK".equals(String.valueOf(reply.get(0)))) {
            return new ConfirmResult.HoldExpired(holdId);
        }

        var berthIds = new ArrayList<Long>(reply.size() - 1);
        for (int i = 1; i < reply.size(); i++) {
            berthIds.add(berthIdOf(pool, ((Long) reply.get(i)).intValue()));
        }
        return new ConfirmResult.Confirmed(bookingId, List.copyOf(berthIds));
    }

    // ---------------------------------------------------------- availability

    @Override
    public AvailabilitySnapshot availability(PoolKey pool, SegmentRange range) {
        Long free =
                scripts.run(
                        "availability",
                        ScriptOutputType.INTEGER,
                        new String[] {freeKey(pool)},
                        String.valueOf(range.fromSeq()),
                        String.valueOf(range.toSeq()),
                        String.valueOf(shapeOf(pool).segmentCount()));

        // stale=false: this reads the live counts. FR-15's 2 s cache sits in front
        // of the search endpoint, not here, so that a cache miss and a cache hit
        // are distinguishable to the caller.
        return new AvailabilitySnapshot(pool, range, free.intValue(), false);
    }

    /**
     * A pool's full mask and free-count state.
     *
     * @param masks occupancy per berth, indexed by pool ordinal
     * @param freeCounts berths free per segment
     */
    public record PoolSnapshot(long[] masks, int[] freeCounts) {}

    /**
     * Reads the pool's entire state.
     *
     * <p>Not a convenience. §9.2 is explicit that a Lua bug returning the right
     * <em>answer</em> via the wrong <em>state</em> — decrementing a free count by
     * 1 instead of {@code passengerCount}, say — passes the contract suite and
     * then silently corrupts availability for the rest of the run. T-7 compares
     * this against the Java reference after every step for exactly that reason,
     * and INV-8 and INV-12 check it post-run.
     */
    @SuppressWarnings("unchecked")
    public PoolSnapshot snapshot(PoolKey pool) {
        List<Object> reply =
                scripts.run(
                        "dump-state",
                        ScriptOutputType.MULTI,
                        new String[] {masksKey(pool), freeKey(pool)});

        var rawMasks = (List<Object>) reply.get(0);
        var rawFree = (List<Object>) reply.get(1);

        // Two unsigned 32-bit halves back into one long. The high word carries
        // segment 63 in its sign bit, so it must be shifted as an unsigned value
        // before being OR-ed in - masking it into a long first is what stops sign
        // extension from setting every bit above 63 (FR-3a).
        var masks = new long[rawMasks.size() / 2];
        for (int i = 0; i < masks.length; i++) {
            long lo = (Long) rawMasks.get(i * 2) & 0xFFFF_FFFFL;
            long hi = (Long) rawMasks.get(i * 2 + 1) & 0xFFFF_FFFFL;
            masks[i] = (hi << 32) | lo;
        }

        var freeCounts = new int[rawFree.size()];
        for (int i = 0; i < freeCounts.length; i++) {
            freeCounts[i] = ((Long) rawFree.get(i)).intValue();
        }

        return new PoolSnapshot(masks, freeCounts);
    }

    /** Reload count after a NOSCRIPT. Non-zero after a chaos run is expected. */
    public long scriptReloadCount() {
        return scripts.reloadCount();
    }

    // ----------------------------------------------------------------- keys

    private String[] poolKeys(PoolKey pool) {
        return new String[] {masksKey(pool), holdsKey(pool), freeKey(pool), detailKey(pool)};
    }

    private static String masksKey(PoolKey pool) {
        return "masks:" + pool.keySuffix();
    }

    private static String holdsKey(PoolKey pool) {
        return "holds:" + pool.keySuffix();
    }

    private static String freeKey(PoolKey pool) {
        return "freecount:" + pool.keySuffix();
    }

    private static String detailKey(PoolKey pool) {
        return "holddetail:" + pool.keySuffix();
    }

    private static String metaKey(PoolKey pool) {
        return "poolmeta:" + pool.keySuffix();
    }

    private static String holdPoolKey(String holdId) {
        return "holdpool:" + holdId;
    }

    // -------------------------------------------------------------- lookups

    private PoolShape shapeOf(PoolKey pool) {
        return shapes.computeIfAbsent(
                pool,
                key -> {
                    var meta = redis.hgetall(metaKey(key));
                    if (meta.isEmpty()) {
                        throw new IllegalStateException("pool not provisioned: " + key);
                    }
                    return new PoolShape(
                            Integer.parseInt(meta.get("berths")),
                            Integer.parseInt(meta.get("segments")));
                });
    }

    private PoolKey poolOfHold(String holdId) {
        String suffix = redis.get(holdPoolKey(holdId));
        if (suffix == null) {
            return null;
        }
        String[] parts = suffix.split(":", 3);
        return new PoolKey(
                Long.parseLong(parts[0]),
                io.tatkalrush.domain.inventory.TravelClass.fromCode(parts[1]),
                io.tatkalrush.domain.inventory.QuotaType.fromCode(parts[2]));
    }

    /**
     * Maps a pool ordinal to a database berth id.
     *
     * <p>Derived rather than stored, matching the seed generator's own scheme. A
     * real deployment would read {@code pool_berths.berth_id} for the ordinal;
     * doing that per allocation would add a Postgres round trip to the hot path
     * that Strategy A exists to keep off it, so Phase 1b caches the mapping
     * alongside the pool shape.
     */
    private long berthIdOf(PoolKey pool, int ordinal) {
        return pool.scheduleId() * 1000L + ordinal;
    }
}
