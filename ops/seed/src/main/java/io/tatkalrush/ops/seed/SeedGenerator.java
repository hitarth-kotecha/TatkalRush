package io.tatkalrush.ops.seed;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic synthetic data generator (FR-48, FR-49, FR-50, FR-69).
 *
 * <p><b>Determinism is the entire point.</b> Strategy A and Strategy B are
 * compared head to head under identical load (§9.4). If the data differs between
 * those two runs, the measured difference might be the data rather than the
 * strategy, and the comparison this project is built around becomes an anecdote.
 * So: one {@link Random} seeded from {@link SeedConfig#seed()}, drawn from in a
 * fixed order, with every timestamp derived from {@link #BASE_DATE} rather than
 * the wall clock.
 *
 * <p>The traps avoided here all look deterministic within a single run:
 * {@code now()}, {@code UUID.randomUUID()}, {@code HashMap} iteration order, and
 * parallel streams. Insertion-ordered maps and explicit loops throughout.
 *
 * <p>AC-0.2 allows 60 seconds. The dominant cost is ~345k {@code pool_berths}
 * rows, handled with batched prepared statements plus
 * {@code reWriteBatchedInserts=true} on the JDBC URL, which collapses a batch of
 * single-row INSERTs into one multi-row INSERT server-side.
 */
public final class SeedGenerator {

    /**
     * Fixed origin for every date in the dataset. {@code LocalDate.now()} would
     * make two runs on different days produce different data and silently void
     * FR-50 — in a way no test would catch unless it happened to run across
     * midnight.
     */
    public static final LocalDate BASE_DATE = LocalDate.of(2026, 10, 1);

    private static final int SCHEDULE_DAYS = 30;
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    /**
     * Share of each class's berths carved out for TATKAL. A berth belongs to
     * exactly ONE pool: GENERAL and TATKAL sell disjoint sets, because a berth
     * present in both could be sold twice through two quotas while each pool's
     * own mask stayed perfectly consistent (FR-10).
     */
    private static final double TATKAL_SHARE = 0.20;

    private static final int BATCH = 5_000;

    private final SeedConfig config;
    private final Random random;

    public SeedGenerator(SeedConfig config) {
        this.config = config;
        this.random = new Random(config.seed());
    }

    // ------------------------------------------------------------------ run

    public SeedStats generate(Connection conn) throws SQLException {
        conn.setAutoCommit(false);

        var stats = new SeedStats();
        long started = System.nanoTime();

        List<Long> stationIds = insertStations(conn, stats);
        List<Train> trains = planTrains(stationIds);

        insertTrains(conn, trains, stats);
        insertTrainStops(conn, trains, stats);
        insertCoachesAndBerths(conn, trains, stats);
        insertUsers(conn, stats);
        insertSchedulesAndPools(conn, trains, stats);

        conn.commit();
        stats.elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        return stats;
    }

    // ------------------------------------------------------------- planning

    /**
     * Decides the shape of every train before any insert happens, so that berth
     * id ranges can be computed arithmetically rather than round-tripped through
     * generated keys. Ids are predictable because the tables are empty and the
     * BIGSERIAL sequences start at 1 — the seed generator owns the database.
     */
    private List<Train> planTrains(List<Long> stationIds) {
        var trains = new ArrayList<Train>();
        long nextBerthId = 1;

        for (int t = 0; t < config.trainCount(); t++) {
            // 8-25 stops (FR-48), bounded by train_stops.seq <= 63 and the
            // 64-bit mask FR-3a constrains.
            int stopCount = 8 + random.nextInt(18);
            List<Long> route = pickRoute(stationIds, stopCount);
            List<CoachLayout> coaches = pickCoaches();

            int berthCount = coaches.stream().mapToInt(CoachLayout::berthCount).sum();

            trains.add(
                    new Train(
                            t + 1L,
                            "1%04d".formatted(2000 + t),
                            "Express %d".formatted(t + 1),
                            route,
                            // FR-49: the first three trains are hot and absorb P3.
                            // Fixed rather than random so a load profile can name
                            // them without reading the database first.
                            t < 3,
                            coaches,
                            nextBerthId));

            nextBerthId += berthCount;
        }
        return trains;
    }

    /** Distinct stations in a fixed order — a route may not revisit a station. */
    private List<Long> pickRoute(List<Long> stationIds, int stopCount) {
        var pool = new ArrayList<>(stationIds);
        var route = new ArrayList<Long>(stopCount);
        for (int i = 0; i < stopCount && !pool.isEmpty(); i++) {
            route.add(pool.remove(random.nextInt(pool.size())));
        }
        return route;
    }

    /**
     * 4-8 coaches across 3-5 classes (FR-48), weighted toward sleeper as on a
     * real train. The weighting is also what lands the dataset near FR-48's ~300k
     * bookable berths: an even split across classes would produce roughly half
     * that, since 1A holds 24 berths to sleeper's 72.
     */
    private List<CoachLayout> pickCoaches() {
        var coaches = new ArrayList<CoachLayout>();

        // 7 or 8 coaches - the TOP of FR-48's 4-8 range, and the realistic end
        // of it: a real Rajdhani runs ~18 coaches, and the SDD's cap of 8 is a
        // laptop-budget concession rather than a claim about trains.
        //
        // It is also what makes FR-48's two numbers agree. Uniform 4-8 with
        // realistic coach sizes yields ~367 berths/train, or ~220k bookable
        // berth-instances - well short of FR-48's "roughly 300k". See the note
        // in docs/design-decisions.md.
        int count = 7 + random.nextInt(2);

        // Guarantee three distinct classes so FR-48's "3-5 classes" holds for
        // every train rather than merely on average.
        coaches.add(CoachLayout.SL);
        coaches.add(CoachLayout.THREE_A);
        coaches.add(CoachLayout.TWO_A);

        // Sleeper-heavy, as a real consist is: SL is the bulk of an Indian
        // Railways train and the AC classes are a handful of coaches.
        CoachLayout[] weighted = {
            CoachLayout.SL, CoachLayout.SL, CoachLayout.SL, CoachLayout.SL, CoachLayout.SL,
            CoachLayout.THREE_A, CoachLayout.THREE_A,
            CoachLayout.TWO_A,
        };
        for (int i = 3; i < count; i++) {
            coaches.add(weighted[random.nextInt(weighted.length)]);
        }
        return coaches;
    }

    // --------------------------------------------------------------- insert

    private List<Long> insertStations(Connection conn, SeedStats stats) throws SQLException {
        // A fixed roster rather than generated names: station codes appear in
        // benchmark reports and demo screenshots, and "NDLS" is legible where
        // "STN-0042" is not.
        String[][] stations = {
            {"NDLS", "New Delhi"}, {"CNB", "Kanpur Central"}, {"ALD", "Prayagraj Jn"},
            {"MGS", "Pt DD Upadhyaya Jn"}, {"PNBE", "Patna Jn"}, {"HWH", "Howrah Jn"},
            {"KOTA", "Kota Jn"}, {"RTM", "Ratlam Jn"}, {"BRC", "Vadodara Jn"},
            {"ST", "Surat"}, {"BCT", "Mumbai Central"}, {"CSMT", "Mumbai CSMT"},
            {"PUNE", "Pune Jn"}, {"SBC", "KSR Bengaluru"}, {"MAS", "MGR Chennai Ctr"},
            {"SC", "Secunderabad Jn"}, {"NGP", "Nagpur Jn"}, {"BPL", "Bhopal Jn"},
            {"JHS", "Jhansi Jn"}, {"GWL", "Gwalior Jn"}, {"AGC", "Agra Cantt"},
            {"LKO", "Lucknow"}, {"GKP", "Gorakhpur Jn"}, {"JAT", "Jammu Tawi"},
            {"ASR", "Amritsar Jn"}, {"CDG", "Chandigarh"}, {"DDN", "Dehradun"},
            {"ADI", "Ahmedabad Jn"}, {"JP", "Jaipur Jn"}, {"JU", "Jodhpur Jn"},
        };

        try (PreparedStatement ps =
                conn.prepareStatement("INSERT INTO stations (code, name) VALUES (?, ?)")) {
            for (String[] s : stations) {
                ps.setString(1, s[0]);
                ps.setString(2, s[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        var ids = new ArrayList<Long>();
        for (long i = 1; i <= stations.length; i++) {
            ids.add(i);
        }
        stats.stations = ids.size();
        return ids;
    }

    private void insertTrains(Connection conn, List<Train> trains, SeedStats stats)
            throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO trains (number, name, origin_station_id, dest_station_id,"
                                + " is_hot) VALUES (?, ?, ?, ?, ?)")) {
            for (Train train : trains) {
                ps.setString(1, train.number());
                ps.setString(2, train.name());
                ps.setLong(3, train.route().get(0));
                ps.setLong(4, train.route().get(train.route().size() - 1));
                ps.setBoolean(5, train.hot());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        stats.trains = trains.size();
        stats.hotTrains = (int) trains.stream().filter(Train::hot).count();
    }

    private void insertTrainStops(Connection conn, List<Train> trains, SeedStats stats)
            throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO train_stops (train_id, station_id, seq, arr_time, dep_time,"
                                + " distance_km) VALUES (?, ?, ?, ?, ?, ?)")) {

            for (Train train : trains) {
                // Cumulative and strictly increasing. FR-67 sums distance over
                // [from_seq, to_seq); a non-monotonic value would produce a
                // NEGATIVE fare and break INV-7 rather than merely look odd.
                double cumulativeKm = 0;
                LocalTime clock = LocalTime.of(6, 0).plusMinutes(random.nextInt(16) * 30L);

                for (int seq = 0; seq < train.route().size(); seq++) {
                    if (seq > 0) {
                        cumulativeKm += 60 + random.nextInt(240);
                        clock = clock.plusMinutes(45 + random.nextInt(90));
                    }
                    ps.setLong(1, train.id());
                    ps.setLong(2, train.route().get(seq));
                    ps.setInt(3, seq);
                    ps.setObject(4, seq == 0 ? null : clock.minusMinutes(5));
                    ps.setObject(5, seq == train.route().size() - 1 ? null : clock);
                    ps.setBigDecimal(6, BigDecimal.valueOf(cumulativeKm));
                    ps.addBatch();
                    stats.trainStops++;
                }
                ps.executeBatch();
            }
        }
    }

    private void insertCoachesAndBerths(Connection conn, List<Train> trains, SeedStats stats)
            throws SQLException {

        try (PreparedStatement coachPs =
                        conn.prepareStatement(
                                "INSERT INTO coaches (train_id, code, travel_class, berth_count)"
                                        + " VALUES (?, ?, ?, ?)");
                PreparedStatement berthPs =
                        conn.prepareStatement(
                                "INSERT INTO berths (coach_id, ordinal, berth_type)"
                                        + " VALUES (?, ?, ?)")) {

            long coachId = 0;

            for (Train train : trains) {
                var perClassIndex = new LinkedHashMap<String, Integer>();

                for (CoachLayout layout : train.coaches()) {
                    coachId++;
                    int n = perClassIndex.merge(layout.travelClass(), 1, Integer::sum);

                    coachPs.setLong(1, train.id());
                    coachPs.setString(2, layout.travelClass() + n);
                    coachPs.setString(3, layout.travelClass());
                    coachPs.setInt(4, layout.berthCount());
                    coachPs.addBatch();
                    stats.coaches++;

                    var types = layout.berthTypes();
                    for (int ordinal = 0; ordinal < types.size(); ordinal++) {
                        berthPs.setLong(1, coachId);
                        berthPs.setInt(2, ordinal);
                        berthPs.setString(3, types.get(ordinal));
                        berthPs.addBatch();
                        stats.berths++;
                    }
                }
                coachPs.executeBatch();
                berthPs.executeBatch();
            }
        }
    }

    private void insertUsers(Connection conn, SeedStats stats) throws SQLException {
        // FR-69, and load-bearing rather than cosmetic: FR-60 caps a user at
        // 10 rps and one k6 VU maps to one distinct user, so too few users makes
        // the harness rate-limit itself. A run with any RATE_LIMITED is INVALID
        // under §19.5 — the report generator refuses to emit it.
        // created_at is set EXPLICITLY. The column defaults to now(), which made
        // two runs of the same seed produce different rows and failed the
        // determinism test - the generator was deterministic all along; the
        // schema default was not. Every DEFAULT now() column the seed touches
        // has to be pinned like this.
        var createdAt = OffsetDateTime.of(BASE_DATE.minusDays(60), LocalTime.MIDNIGHT, IST);

        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO users (external_ref, created_at) VALUES (?, ?)")) {
            for (int i = 0; i < config.userCount(); i++) {
                ps.setString(1, "user-%06d".formatted(i));
                ps.setObject(2, createdAt);
                ps.addBatch();
                if ((i + 1) % BATCH == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        stats.users = config.userCount();
    }

    private void insertSchedulesAndPools(Connection conn, List<Train> trains, SeedStats stats)
            throws SQLException {

        long scheduleId = 0;
        long poolId = 0;

        try (PreparedStatement schedPs =
                        conn.prepareStatement(
                                "INSERT INTO schedules (train_id, journey_date, status,"
                                        + " departure_at) VALUES (?, ?, 'OPEN', ?)");
                PreparedStatement poolPs =
                        conn.prepareStatement(
                                "INSERT INTO quota_pools (schedule_id, travel_class, quota_type,"
                                        + " total_berths, opens_at) VALUES (?, ?, ?, ?, ?)");
                PreparedStatement poolBerthPs =
                        conn.prepareStatement(
                                "INSERT INTO pool_berths (pool_id, berth_id, pool_ordinal)"
                                        + " VALUES (?, ?, ?)")) {

            for (Train train : trains) {
                // Built once per train and reused across all 30 journey dates:
                // berths are physical and shared between dates. This is exactly
                // why pool_berths (~345k rows) is 30x the berths table (~11.5k).
                Map<String, List<Long>> berthsByClass = berthIdsByClass(train);

                for (int day = 0; day < SCHEDULE_DAYS; day++) {
                    scheduleId++;
                    LocalDate journeyDate = BASE_DATE.plusDays(day);

                    // ORDER MATTERS, and getting it wrong is not a style issue.
                    // pool_berths has a foreign key to quota_pools, which has one
                    // to schedules. Because pool_berths is flushed every BATCH
                    // rows rather than at the end, a parent still sitting
                    // unflushed in its own batch does not exist yet as far as the
                    // server is concerned. Each level is therefore executed
                    // before the level below it is queued.
                    schedPs.setLong(1, train.id());
                    schedPs.setObject(2, journeyDate);
                    schedPs.setObject(3, OffsetDateTime.of(journeyDate, LocalTime.of(16, 55), IST));
                    schedPs.addBatch();
                    schedPs.executeBatch();
                    stats.schedules++;

                    // TATKAL unlocks at a fixed wall-clock instant the day before
                    // departure (FR-28). Unlock is a PURE FUNCTION of clock time
                    // evaluated per request (FR-30): no job, therefore no moment
                    // where a pool's state depends on whether a job ran.
                    OffsetDateTime tatkalOpensAt =
                            OffsetDateTime.of(journeyDate.minusDays(1), LocalTime.of(10, 0), IST);

                    // Pass 1: the pools for this schedule, flushed before any
                    // pool_berths row references them.
                    var poolsByClass = new LinkedHashMap<String, PoolSplit>();

                    for (var entry : berthsByClass.entrySet()) {
                        List<Long> berthIds = entry.getValue();
                        int tatkalCount = (int) Math.round(berthIds.size() * TATKAL_SHARE);
                        int generalCount = berthIds.size() - tatkalCount;

                        long generalPoolId = ++poolId;
                        poolPs.setLong(1, scheduleId);
                        poolPs.setString(2, entry.getKey());
                        poolPs.setString(3, "GENERAL");
                        poolPs.setInt(4, generalCount);
                        poolPs.setObject(5, null); // always open
                        poolPs.addBatch();

                        long tatkalPoolId = ++poolId;
                        poolPs.setLong(1, scheduleId);
                        poolPs.setString(2, entry.getKey());
                        poolPs.setString(3, "TATKAL");
                        poolPs.setInt(4, tatkalCount);
                        poolPs.setObject(5, tatkalOpensAt);
                        poolPs.addBatch();
                        stats.quotaPools += 2;

                        poolsByClass.put(
                                entry.getKey(),
                                new PoolSplit(generalPoolId, tatkalPoolId, generalCount));
                    }
                    poolPs.executeBatch();

                    // Pass 2: membership. Disjoint by construction — a berth in
                    // BOTH pools could sell twice through two quotas while each
                    // pool's own mask stayed perfectly consistent, an overbooking
                    // no allocator could detect because neither is wrong.
                    for (var entry : berthsByClass.entrySet()) {
                        PoolSplit split = poolsByClass.get(entry.getKey());
                        List<Long> berthIds = entry.getValue();

                        for (int i = 0; i < berthIds.size(); i++) {
                            boolean tatkal = i >= split.generalCount();
                            poolBerthPs.setLong(1, tatkal ? split.tatkalPoolId() : split.generalPoolId());
                            poolBerthPs.setLong(2, berthIds.get(i));
                            poolBerthPs.setInt(3, tatkal ? i - split.generalCount() : i);
                            poolBerthPs.addBatch();
                            stats.poolBerths++;

                            if (stats.poolBerths % BATCH == 0) {
                                poolBerthPs.executeBatch();
                            }
                        }
                    }
                }
                poolBerthPs.executeBatch();
            }
        }
    }

    /** Berth ids for one train, grouped by travel class, in ordinal order. */
    private Map<String, List<Long>> berthIdsByClass(Train train) {
        // LinkedHashMap, not HashMap: iteration order feeds pool_ordinal and
        // therefore the bit positions the allocator uses. HashMap ordering is
        // stable within a JVM version but is not a documented guarantee, and
        // FR-50 promises reproducibility across runs and machines.
        var byClass = new LinkedHashMap<String, List<Long>>();

        long berthId = train.firstBerthId();
        for (CoachLayout layout : train.coaches()) {
            var list = byClass.computeIfAbsent(layout.travelClass(), k -> new ArrayList<>());
            for (int i = 0; i < layout.berthCount(); i++) {
                list.add(berthId++);
            }
        }
        return byClass;
    }

    // --------------------------------------------------------------- types

    private record Train(
            long id,
            String number,
            String name,
            List<Long> route,
            boolean hot,
            List<CoachLayout> coaches,
            long firstBerthId) {}

    /** The GENERAL/TATKAL split of one class's berths within one schedule. */
    private record PoolSplit(long generalPoolId, long tatkalPoolId, int generalCount) {}
}
