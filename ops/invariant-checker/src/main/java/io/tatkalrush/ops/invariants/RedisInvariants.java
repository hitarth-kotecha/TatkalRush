package io.tatkalrush.ops.invariants;

import io.lettuce.core.Range;
import io.lettuce.core.api.sync.RedisCommands;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * §14's three checks that reach into Redis — "the strongest checks and the ones
 * that catch the subtle bugs".
 *
 * <h2>INV-8 and INV-12 ask different questions</h2>
 *
 * <ul>
 *   <li><b>INV-8</b>: does Redis agree with the durable record? Masks against
 *       {@code seat_allocations}.
 *   <li><b>INV-12</b>: does Redis agree with <em>itself</em>? Free counts against
 *       the masks they summarise.
 * </ul>
 *
 * <p>Deliberately not merged. Masks-wrong and counts-wrong are different bugs with
 * different fixes, and one check spanning both would report that something is
 * broken without saying which layer. Two checks that look similar and
 * <b>localise differently</b> are worth more than one covering both.
 *
 * <h2>All three are quiesce-only</h2>
 *
 * <p>During load, Redis and Postgres are legitimately out of step — a berth is
 * allocated in Redis before its booking row commits, and a free count read between
 * two writes is momentarily stale. §14 says so outright. {@link InvariantChecker}
 * skips these in continuous mode rather than reporting divergence that is expected.
 */
public final class RedisInvariants {

    private RedisInvariants() {}

    /**
     * @param holdTtlMillis FR-17's 120 s, so INV-5's threshold moves with it
     */
    public static List<Invariant> all(RedisCommands<String, String> redis, long holdTtlMillis) {
        return List.of(
                noStaleHolds(redis, holdTtlMillis),
                masksMatchPostgres(redis),
                freeCountsMatchMasks(redis));
    }

    // ── INV-5 ───────────────────────────────────────────────────────────────

    /**
     * A hold that outlived its TTL by more than the grace period.
     *
     * <p>The 30 seconds is not padding. §9.2 reaps two ways — lazily inside
     * {@code allocate}, and from a background sweep — so a hold at {@code TTL + 1s}
     * is one the reaper has not reached yet, not a leak. On an idle pool nothing
     * triggers the lazy path at all, and a zero grace would have this fire
     * constantly on a system doing nothing wrong.
     *
     * <p>What it catches is the reaper being <em>broken</em> rather than behind:
     * berths held by nobody, invisible to Postgres because the booking is long
     * since expired, and unsellable until something rebuilds the pool.
     */
    public static Invariant noStaleHolds(RedisCommands<String, String> redis, long holdTtlMillis) {
        return new Invariant() {
            @Override
            public String id() {
                return "INV-5";
            }

            @Override
            public String description() {
                return "No hold older than TTL + 30 s exists in Redis";
            }

            @Override
            public boolean quiesceOnly() {
                return true;
            }

            @Override
            public List<String> violations(CheckContext context) {
                long cutoff = System.currentTimeMillis() - 30_000;
                var violations = new ArrayList<String>();

                for (String holdsKey : redis.keys("holds:*")) {
                    // The ZSET's score is the hold's EXPIRY, not its creation - so
                    // anything scoring below (now - grace) expired more than the
                    // grace period ago and should have been swept.
                    var stale = redis.zrangebyscore(holdsKey, Range.create(0d, (double) cutoff));
                    for (String holdId : stale) {
                        Double score = redis.zscore(holdsKey, holdId);
                        violations.add(
                                "pool=%s, holdId=%s, expired_at=%s, overdue_by_ms=%d"
                                        .formatted(
                                                holdsKey,
                                                holdId,
                                                score == null ? "?" : score.longValue(),
                                                score == null
                                                        ? -1
                                                        : System.currentTimeMillis()
                                                                - score.longValue()));
                    }
                }
                return capped(violations);
            }
        };
    }

    // ── INV-8 ───────────────────────────────────────────────────────────────

    /**
     * Redis masks against Postgres allocations, rebuilt from scratch.
     *
     * <p>The expected mask for a berth is computed <b>from {@code seat_allocations}
     * alone</b>. Nothing here asks the allocator what it thinks the mask should be:
     * that would compare the allocator against itself and pass through any bug the
     * two sides share.
     *
     * <p>A bit set with no allocation row behind it is a phantom sold-out berth —
     * seats nobody can buy and nobody can explain. A row with no bit is worse: the
     * berth is offered twice and the exclusion constraint catches it at
     * confirmation, after the money moved.
     *
     * <p><b>Holds are excluded from the comparison, and that is a real limitation.</b>
     * A live hold sets mask bits with no {@code seat_allocations} row - allocations
     * are written at confirmation (FR-25). So this can only be trusted once holds
     * have drained, which is what quiesced means here and why the check says so
     * rather than trying to subtract them.
     */
    public static Invariant masksMatchPostgres(RedisCommands<String, String> redis) {
        return new Invariant() {
            @Override
            public String id() {
                return "INV-8";
            }

            @Override
            public String description() {
                return "Redis masks match Postgres seat_allocations exactly (quiesced)";
            }

            @Override
            public boolean quiesceOnly() {
                return true;
            }

            @Override
            public List<String> violations(CheckContext context) {
                var violations = new ArrayList<String>();

                for (var pool : poolsInRedis(redis)) {
                    String masksBlob = redis.get("masks:" + pool);
                    String freeBlob = redis.get("freecount:" + pool);
                    if (masksBlob == null || freeBlob == null) {
                        violations.add("pool=%s has no masks or freecount key".formatted(pool));
                        continue;
                    }

                    long liveHolds = redis.zcard("holds:" + pool);
                    if (liveHolds > 0) {
                        // Not a violation - a statement that the check could not be
                        // made. Reporting "matched" here would be a green light
                        // nobody earned.
                        violations.add(
                                "pool=%s has %d live hold(s); masks legitimately differ from"
                                    + " seat_allocations until they drain (§14: quiesce first)"
                                        .formatted(pool, liveHolds));
                        continue;
                    }

                    PoolSnapshot snapshot;
                    try {
                        snapshot = PoolSnapshot.decode(masksBlob, freeBlob);
                    } catch (RuntimeException e) {
                        violations.add("pool=%s: %s".formatted(pool, e.getMessage()));
                        continue;
                    }

                    long[] expected = expectedMasks(context, pool, snapshot.berthCount());
                    if (expected == null) {
                        violations.add("pool=%s is in Redis but not in quota_pools".formatted(pool));
                        continue;
                    }

                    for (int ordinal = 0; ordinal < snapshot.berthCount(); ordinal++) {
                        if (snapshot.masks()[ordinal] != expected[ordinal]) {
                            violations.add(
                                    "pool=%s, ordinal=%d, redis=%s, postgres=%s"
                                            .formatted(
                                                    pool,
                                                    ordinal,
                                                    Long.toBinaryString(snapshot.masks()[ordinal]),
                                                    Long.toBinaryString(expected[ordinal])));
                        }
                    }
                }
                return capped(violations);
            }
        };
    }

    // ── INV-12 ──────────────────────────────────────────────────────────────

    /**
     * Free counts against the masks they summarise.
     *
     * <p>DD-012's subject. The count exists because deriving availability from
     * masks on every read is O(berths) on the hottest path in the system — and it
     * is maintained incrementally by seven writers, any of which can drift it.
     *
     * <p>It sits directly upstream of {@code allocation_conflicts_total}, which is
     * the metric §9.4's whole conclusion rests on. A count that reads high offers
     * berths that do not exist; one that reads low hides berths that do.
     */
    public static Invariant freeCountsMatchMasks(RedisCommands<String, String> redis) {
        return new Invariant() {
            @Override
            public String id() {
                return "INV-12";
            }

            @Override
            public String description() {
                return "Free counts match masks exactly (quiesced)";
            }

            @Override
            public boolean quiesceOnly() {
                return true;
            }

            @Override
            public List<String> violations(CheckContext context) {
                var violations = new ArrayList<String>();

                for (var pool : poolsInRedis(redis)) {
                    String masksBlob = redis.get("masks:" + pool);
                    String freeBlob = redis.get("freecount:" + pool);
                    if (masksBlob == null || freeBlob == null) {
                        continue;
                    }

                    PoolSnapshot snapshot;
                    try {
                        snapshot = PoolSnapshot.decode(masksBlob, freeBlob);
                    } catch (RuntimeException e) {
                        violations.add("pool=%s: %s".formatted(pool, e.getMessage()));
                        continue;
                    }

                    int[] recomputed = snapshot.freeCountsFromMasks();
                    for (int segment = 0; segment < recomputed.length; segment++) {
                        if (recomputed[segment] != snapshot.freeCounts()[segment]) {
                            violations.add(
                                    "pool=%s, segment=%d, stored=%d, recomputed=%d, drift=%d"
                                            .formatted(
                                                    pool,
                                                    segment,
                                                    snapshot.freeCounts()[segment],
                                                    recomputed[segment],
                                                    snapshot.freeCounts()[segment]
                                                            - recomputed[segment]));
                        }
                    }
                }
                return capped(violations);
            }
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Pool key suffixes, discovered from Redis rather than assumed. */
    private static List<String> poolsInRedis(RedisCommands<String, String> redis) {
        return redis.keys("masks:*").stream().map(key -> key.substring("masks:".length())).sorted().toList();
    }

    /**
     * What the masks would be if {@code seat_allocations} were the only truth.
     *
     * <p>Built by OR-ing each allocation's segment range onto its berth's ordinal.
     * The ordinal comes from {@code pool_berths.pool_ordinal}, which §10.5 calls
     * "the bridge between a database row and a bit position in Redis" — so the
     * mapping is read from the schema rather than derived by arithmetic the
     * allocator also performs.
     *
     * @return {@code null} when the pool is not in {@code quota_pools} at all
     */
    private static long[] expectedMasks(CheckContext context, String poolSuffix, int berthCount) {
        String[] parts = poolSuffix.split(":");
        if (parts.length != 3) {
            return null;
        }

        // Does this pool exist at all? Without asking, a suffix that matches no
        // row produces an all-zero expected mask and INV-8 reports every set bit
        // as a violation - technically true and actively misleading, sending a
        // reader after an allocator bug when the real fault is a stale Redis key
        // or a mistyped suffix.
        try (Statement st = context.connection().createStatement();
                ResultSet rs =
                        st.executeQuery(
                                """
                                SELECT 1 FROM quota_pools
                                WHERE schedule_id = %s AND travel_class = '%s'
                                  AND quota_type = '%s'
                                """
                                        .formatted(parts[0], parts[1], parts[2]))) {
            if (!rs.next()) {
                return null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("looking up pool " + poolSuffix, e);
        }

        var masks = new long[berthCount];
        String sql =
                """
                SELECT pb.pool_ordinal,
                       lower(sa.seg_range) AS from_seq,
                       upper(sa.seg_range) AS to_seq
                FROM seat_allocations sa
                JOIN quota_pools q
                  ON q.schedule_id = sa.schedule_id
                 AND q.travel_class = '%s' AND q.quota_type = '%s'
                JOIN pool_berths pb
                  ON pb.pool_id = q.id AND pb.berth_id = sa.berth_id
                JOIN bookings b
                  ON b.id = sa.booking_id AND b.travel_class = '%s' AND b.quota_type = '%s'
                WHERE sa.schedule_id = %s
                """
                        .formatted(parts[1], parts[2], parts[1], parts[2], parts[0]);

        try (Statement st = context.connection().createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                int ordinal = rs.getInt("pool_ordinal");
                if (ordinal < 0 || ordinal >= berthCount) {
                    continue;
                }
                for (int seg = rs.getInt("from_seq"); seg < rs.getInt("to_seq"); seg++) {
                    masks[ordinal] |= 1L << seg;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("rebuilding expected masks for " + poolSuffix, e);
        }

        return masks;
    }

    private static List<String> capped(List<String> violations) {
        if (violations.size() <= CheckContext.MAX_ROWS_REPORTED) {
            return violations;
        }
        var capped = new ArrayList<>(violations.subList(0, CheckContext.MAX_ROWS_REPORTED));
        capped.add(
                "... and %d more (%d violating rows in total)"
                        .formatted(
                                violations.size() - CheckContext.MAX_ROWS_REPORTED,
                                violations.size()));
        return capped;
    }
}
