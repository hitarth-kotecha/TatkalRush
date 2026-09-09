package io.tatkalrush.application.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AvailabilityCache;
import io.tatkalrush.application.ports.InMemorySeatAllocator;
import io.tatkalrush.application.ports.TrainSearchQuery;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TatkalWindow;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code API-1}'s use case (FR-12 … FR-15).
 *
 * <p>Two of these are worth reading before the rest. {@code aCacheMissIsNotAZero}
 * exists because a cache that answers "0" for "I do not know" reports a full train
 * as sold out, and every layer here would pass that number along without noticing.
 * {@code anUnreadablePoolDoesNotFailTheWholeSearch} exists because chaos scenario
 * C2 makes that state reachable in production, not just in a test.
 */
class SearchTrainsTest {

    private static final LocalDate JOURNEY = LocalDate.of(2026, 10, 2);

    /**
     * 2026-10-01 06:00 UTC is 11:30 IST, so sleeper TATKAL (11:00 IST on D-1) is
     * open and AC TATKAL (10:00 IST) is too.
     */
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");

    /** 08:30 IST on D-1: before both windows. */
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-10-01T03:00:00Z");

    private static final SegmentRange RANGE = new SegmentRange(0, 4);
    private static final int SEGMENTS = 8;

    private FakeTrainSearchQuery routes;
    private FakeAvailabilityCache cache;
    private InMemorySeatAllocator allocator;
    private SearchTrains searchTrains;

    @BeforeEach
    void setUp() {
        routes = new FakeTrainSearchQuery();
        cache = new FakeAvailabilityCache();
        allocator = new InMemorySeatAllocator();
        searchTrains = new SearchTrains(routes, allocator, cache);
        routes.stations.addAll(Set.of("NDLS", "BCT", "ADI"));
    }

    // ── the shape of an answer ──────────────────────────────────────────────

    @Nested
    @DisplayName("FR-12: every pool on the route")
    class RouteResolution {

        @Test
        void everyClassAndQuotaOnEveryTrainIsListed() {
            provision(pool(1, TravelClass.SL, QuotaType.GENERAL), 72);
            provision(pool(1, TravelClass.SL, QuotaType.TATKAL), 12);
            provision(pool(2, TravelClass.AC3, QuotaType.GENERAL), 64);

            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 72, "12951"));
            routes.add(onRoute(1, TravelClass.SL, QuotaType.TATKAL, 12, "12951"));
            routes.add(onRoute(2, TravelClass.AC3, QuotaType.GENERAL, 64, "12953"));

            var found = found(search(NOW));

            assertEquals(2, found.trains().size(), "two schedules, two trains");
            assertEquals(2, found.trains().get(0).classes().size(), "SL GENERAL and SL TATKAL");
            assertEquals(1, found.trains().get(1).classes().size());
            assertEquals("12951", found.trains().get(0).trainNumber());
        }

        @Test
        void poolsAreGroupedByScheduleInTheOrderTheQueryReturnedThem() {
            provision(pool(7, TravelClass.SL, QuotaType.GENERAL), 10);
            provision(pool(3, TravelClass.SL, QuotaType.GENERAL), 10);
            provision(pool(7, TravelClass.AC3, QuotaType.GENERAL), 10);

            // Deliberately interleaved: a grouping that reorders would put both
            // 7s together and change the train order, and two benchmark runs that
            // differ in row order are needlessly hard to diff.
            routes.add(onRoute(7, TravelClass.SL, QuotaType.GENERAL, 10, "A"));
            routes.add(onRoute(3, TravelClass.SL, QuotaType.GENERAL, 10, "B"));
            routes.add(onRoute(7, TravelClass.AC3, QuotaType.GENERAL, 10, "A"));

            var found = found(search(NOW));

            assertEquals(List.of(7L, 3L), found.trains().stream().map(t -> t.scheduleId()).toList());
            assertEquals(2, found.trains().get(0).classes().size());
        }

        @Test
        void theClassFilterReachesTheQuery() {
            provision(pool(1, TravelClass.SL, QuotaType.GENERAL), 72);
            provision(pool(1, TravelClass.AC3, QuotaType.GENERAL), 64);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 72, "12951"));
            routes.add(onRoute(1, TravelClass.AC3, QuotaType.GENERAL, 64, "12951"));

            var found =
                    found(
                            searchTrains.search(
                                    new SearchTrains.SearchCommand(
                                            "NDLS",
                                            "BCT",
                                            JOURNEY,
                                            Optional.of(TravelClass.SL),
                                            NOW)));

            assertEquals(
                    Optional.of(TravelClass.SL),
                    routes.lastQuery.travelClass(),
                    "the filter must reach the query, not be applied afterwards");
            assertEquals(1, found.trains().get(0).classes().size());
            assertEquals(TravelClass.SL, found.trains().get(0).classes().get(0).travelClass());
        }
    }

    // ── FR-13 ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FR-13: the minimum across the range")
    class Availability {

        @Test
        void theCountIsTheMinimumOverTheRequestedSegmentsNotTheAverage() {
            PoolKey key = pool(1, TravelClass.SL, QuotaType.GENERAL);
            provision(key, 4);
            // One berth taken on segment 3 only. Every other segment still has 4.
            allocator.allocate(
                    new AllocationRequest(key, new SegmentRange(3, 4), 1, "h1", NOW, 60_000));

            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 4, "12951"));

            var found = found(search(NOW));

            // An average over [0,4) would be (4+4+4+3)/4 = 3.75 -> 3 or 4 depending
            // on rounding. The minimum is 3, and it is the only number that can be
            // sold: a journey needs the same berth for its whole length.
            assertEquals(3, found.trains().get(0).classes().get(0).freeBerths());
        }

        @Test
        void theRangeAskedAboutIsTheOneTheTrainUses() {
            PoolKey key = pool(1, TravelClass.SL, QuotaType.GENERAL);
            provision(key, 4);
            allocator.allocate(
                    new AllocationRequest(key, new SegmentRange(5, 7), 2, "h1", NOW, 60_000));

            // This train's journey is [0,4) - outside the occupied segments.
            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 4, "12951"));

            assertEquals(
                    4,
                    found(search(NOW)).trains().get(0).classes().get(0).freeBerths(),
                    "berths taken on segments this journey does not use must not reduce it");
        }
    }

    // ── FR-15 ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FR-15: the two-second cache")
    class Cache {

        @Test
        void aMissIsComputedThenCachedAndIsNotStale() {
            PoolKey key = pool(1, TravelClass.SL, QuotaType.GENERAL);
            provision(key, 40);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 40, "12951"));

            var found = found(search(NOW));

            assertEquals(0, found.cacheHits());
            assertEquals(1, found.cacheMisses());
            assertFalse(
                    found.trains().get(0).classes().get(0).stale(),
                    "computed on this request, so not stale");
            assertEquals(
                    Map.of(new AvailabilityCache.Key(key, RANGE), 40),
                    cache.writes.get(0),
                    "the miss is written back, or the next request misses too");
        }

        @Test
        void aHitIsMarkedStaleAndNeverReachesTheAllocator() {
            PoolKey key = pool(1, TravelClass.SL, QuotaType.GENERAL);
            // Deliberately NOT provisioned in the allocator. If the use case
            // consults it anyway the fake throws, and this test fails loudly rather
            // than silently proving nothing.
            cache.seed(new AvailabilityCache.Key(key, RANGE), 17);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 40, "12951"));

            var found = found(search(NOW));

            assertEquals(17, found.trains().get(0).classes().get(0).freeBerths());
            assertTrue(found.trains().get(0).classes().get(0).stale());
            assertEquals(1, found.cacheHits());
            assertEquals(0, found.cacheMisses());
            assertTrue(cache.writes.isEmpty(), "nothing new to write");
        }

        @Test
        void aCacheMissIsNotAZero() {
            PoolKey key = pool(1, TravelClass.SL, QuotaType.GENERAL);
            provision(key, 40);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 40, "12951"));

            // The cache is empty. A cache that answered 0 for "not present" - or a
            // use case that read a missing entry as an int - would report a train
            // with 40 free berths as sold out, and every layer above would repeat
            // it without suspicion.
            assertEquals(40, found(search(NOW)).trains().get(0).classes().get(0).freeBerths());
        }

        @Test
        void everyPoolIsReadInOneBatch() {
            for (int schedule = 1; schedule <= 3; schedule++) {
                provision(pool(schedule, TravelClass.SL, QuotaType.GENERAL), 10);
                provision(pool(schedule, TravelClass.SL, QuotaType.TATKAL), 5);
                routes.add(onRoute(schedule, TravelClass.SL, QuotaType.GENERAL, 10, "T" + schedule));
                routes.add(onRoute(schedule, TravelClass.SL, QuotaType.TATKAL, 5, "T" + schedule));
            }

            search(NOW);

            assertEquals(1, cache.reads.size(), "one round trip, not one per pool");
            assertEquals(6, cache.reads.get(0).size());
        }

        @Test
        void aFailingCacheDegradesToASlowSearchNotAFailedOne() {
            PoolKey key = pool(1, TravelClass.SL, QuotaType.GENERAL);
            provision(key, 40);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 40, "12951"));
            cache.failing = true;

            // Every value here is one Lua call away from being recomputed, so an
            // unreachable cache must cost latency and nothing else.
            assertEquals(40, found(search(NOW)).trains().get(0).classes().get(0).freeBerths());
        }
    }

    // ── FR-28, FR-42 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("bookability: the Tatkal window and the chart")
    class Bookability {

        @Test
        void aLockedTatkalPoolReportsWhenItOpens() {
            provision(pool(1, TravelClass.SL, QuotaType.TATKAL), 12);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.TATKAL, 12, "12951"));

            var travelClass = found(search(BEFORE_WINDOW)).trains().get(0).classes().get(0);

            assertFalse(travelClass.bookable());
            assertEquals(
                    TatkalWindow.opensAt(JOURNEY, TravelClass.SL),
                    travelClass.opensAt(),
                    "FR-29 wants the instant, not merely the fact");
        }

        @Test
        void aLockedPoolStillReportsItsAvailability() {
            provision(pool(1, TravelClass.SL, QuotaType.TATKAL), 12);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.TATKAL, 12, "12951"));

            // A passenger deciding whether to wait for 11:00 wants to know how many
            // berths are waiting there. "Locked" is about permission, not inventory.
            assertEquals(
                    12,
                    found(search(BEFORE_WINDOW)).trains().get(0).classes().get(0).freeBerths());
        }

        @Test
        void anOpenTatkalPoolIsBookableAndReportsNoOpeningTime() {
            provision(pool(1, TravelClass.SL, QuotaType.TATKAL), 12);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.TATKAL, 12, "12951"));

            var travelClass = found(search(NOW)).trains().get(0).classes().get(0);

            assertTrue(travelClass.bookable());
            assertNull(travelClass.opensAt(), "already open; there is nothing to wait for");
        }

        @Test
        void generalIsAlwaysBookableEvenBeforeTheTatkalWindow() {
            provision(pool(1, TravelClass.SL, QuotaType.GENERAL), 72);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 72, "12951"));

            var travelClass = found(search(BEFORE_WINDOW)).trains().get(0).classes().get(0);

            assertTrue(travelClass.bookable(), "FR-8: only TATKAL has a window");
            assertNull(travelClass.opensAt());
        }

        @Test
        void aChartedScheduleIsListedButNotBookable() {
            provision(pool(1, TravelClass.SL, QuotaType.GENERAL), 72);
            routes.add(
                    charted(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 72, "12951")));

            var travelClass = found(search(NOW)).trains().get(0).classes().get(0);

            assertFalse(travelClass.bookable(), "FR-42 closes booking at chart time");
            assertNotNull(
                    travelClass.freeBerths(),
                    "still listed with its count - a passenger searching a charted "
                            + "train should be told it is charted, not that it does not run");
        }
    }

    // ── empty results ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("nothing found, for two different reasons")
    class NothingFound {

        @Test
        void anUnknownStationIsSaidSoRatherThanReportedAsNoTrains() {
            var result =
                    searchTrains.search(
                            new SearchTrains.SearchCommand(
                                    "NDLS", "XXXX", JOURNEY, Optional.empty(), NOW));

            var unknown = assertInstanceOf(SearchTrains.Result.UnknownStation.class, result);
            assertEquals(List.of("XXXX"), unknown.codes());
        }

        @Test
        void bothStationsUnknownAreBothNamed() {
            var result =
                    searchTrains.search(
                            new SearchTrains.SearchCommand(
                                    "AAAA", "ZZZZ", JOURNEY, Optional.empty(), NOW));

            assertEquals(
                    List.of("AAAA", "ZZZZ"),
                    assertInstanceOf(SearchTrains.Result.UnknownStation.class, result).codes());
        }

        @Test
        void aRealRouteWithNoTrainsIsAnEmptyResultNotAnError() {
            var found =
                    found(
                            searchTrains.search(
                                    new SearchTrains.SearchCommand(
                                            "NDLS", "ADI", JOURNEY, Optional.empty(), NOW)));

            assertTrue(found.trains().isEmpty());
            assertEquals(0, found.cacheHits());
            assertEquals(0, found.cacheMisses());
        }

        @Test
        void theStationCheckCostsNothingWhenTrainsWereFound() {
            provision(pool(1, TravelClass.SL, QuotaType.GENERAL), 72);
            routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 72, "12951"));

            search(NOW);

            assertEquals(
                    0,
                    routes.stationChecks,
                    "the diagnostic query must not run on the endpoint's hot path");
        }
    }

    // ── degradation ─────────────────────────────────────────────────────────

    @Test
    void anUnreadablePoolDoesNotFailTheWholeSearch() {
        // Pool 1 is provisioned; pool 2 is not, so the allocator throws for it.
        // Reachable in production: between a pool's creation in Postgres and its
        // initialisation in Redis, and after chaos scenario C2's FLUSHALL.
        provision(pool(1, TravelClass.SL, QuotaType.GENERAL), 40);
        routes.add(onRoute(1, TravelClass.SL, QuotaType.GENERAL, 40, "12951"));
        routes.add(onRoute(2, TravelClass.SL, QuotaType.GENERAL, 64, "12953"));

        var found = found(search(NOW));

        assertEquals(2, found.trains().size(), "the readable train is still answered");
        assertEquals(40, found.trains().get(0).classes().get(0).freeBerths());

        var broken = found.trains().get(1).classes().get(0);
        assertNull(broken.freeBerths(), "null, never a fabricated zero");
        assertFalse(broken.stale(), "nothing was computed, so nothing is stale");
        assertEquals(64, broken.totalBerths(), "the pool's size is known from Postgres");
    }

    @Test
    void anUnreadablePoolIsNotWrittenToTheCache() {
        routes.add(onRoute(2, TravelClass.SL, QuotaType.GENERAL, 64, "12953"));

        search(NOW);

        assertTrue(
                cache.writes.isEmpty(),
                "caching a failure would make the next 2 s of searches repeat it");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private SearchTrains.Result search(Instant now) {
        return searchTrains.search(
                new SearchTrains.SearchCommand("NDLS", "BCT", JOURNEY, Optional.empty(), now));
    }

    private static SearchTrains.Result.Found found(SearchTrains.Result result) {
        return assertInstanceOf(SearchTrains.Result.Found.class, result);
    }

    private static PoolKey pool(long scheduleId, TravelClass travelClass, QuotaType quota) {
        return new PoolKey(scheduleId, travelClass, quota);
    }

    private void provision(PoolKey key, int berths) {
        allocator.provision(key, berths, SEGMENTS);
    }

    private static TrainSearchQuery.PoolOnRoute onRoute(
            long scheduleId,
            TravelClass travelClass,
            QuotaType quota,
            int berths,
            String trainNumber) {

        return new TrainSearchQuery.PoolOnRoute(
                pool(scheduleId, travelClass, quota),
                trainNumber,
                "Test Express",
                false,
                RANGE,
                SEGMENTS,
                berths,
                JOURNEY,
                Instant.parse("2026-10-02T10:00:00Z"),
                LocalTime.of(15, 30),
                LocalTime.of(23, 45),
                new BigDecimal("1384.00"),
                false);
    }

    private static TrainSearchQuery.PoolOnRoute charted(TrainSearchQuery.PoolOnRoute pool) {
        return new TrainSearchQuery.PoolOnRoute(
                pool.pool(),
                pool.trainNumber(),
                pool.trainName(),
                pool.hot(),
                pool.range(),
                pool.segmentCount(),
                pool.totalBerths(),
                pool.journeyDate(),
                pool.departureAt(),
                pool.departsAt(),
                pool.arrivesAt(),
                pool.distanceKm(),
                true);
    }

    /**
     * Applies the class filter itself, because the real SQL does.
     *
     * <p>A fake that ignored the filter would make
     * {@code theClassFilterReachesTheQuery} pass whether or not the filter was
     * passed down — the test would be asserting against the fake's laxness rather
     * than the use case's behaviour.
     */
    private static final class FakeTrainSearchQuery implements TrainSearchQuery {

        final List<PoolOnRoute> pools = new ArrayList<>();
        final Set<String> stations = new LinkedHashSet<>();
        Query lastQuery;
        int stationChecks;

        void add(PoolOnRoute pool) {
            pools.add(pool);
        }

        @Override
        public List<PoolOnRoute> poolsOnRoute(Query query) {
            lastQuery = query;
            // "BCT" is the only destination any seeded pool serves here, so a query
            // for ADI finds nothing - which is what
            // aRealRouteWithNoTrainsIsAnEmptyResultNotAnError needs.
            if (!"BCT".equals(query.toStationCode())) {
                return List.of();
            }
            return pools.stream()
                    .filter(
                            p ->
                                    query.travelClass().isEmpty()
                                            || p.pool().travelClass() == query.travelClass().get())
                    .toList();
        }

        @Override
        public List<String> stationsExist(List<String> codes) {
            stationChecks++;
            return codes.stream().filter(stations::contains).toList();
        }
    }

    /**
     * No more permissive than {@link AvailabilityCache} says.
     *
     * <p>Specifically: a miss is an <em>absent</em> entry, never a zero, and a
     * failure returns an empty map rather than throwing. Both match what
     * {@code RedisAvailabilityCache} does; a fake that returned 0 for a miss would
     * hide the exact bug {@code aCacheMissIsNotAZero} exists to catch.
     */
    private static final class FakeAvailabilityCache implements AvailabilityCache {

        private final Map<Key, Integer> entries = new LinkedHashMap<>();
        final List<List<Key>> reads = new ArrayList<>();
        final List<Map<Key, Integer>> writes = new ArrayList<>();
        boolean failing;

        void seed(Key key, int freeBerths) {
            entries.put(key, freeBerths);
        }

        @Override
        public Map<Key, Integer> getAll(List<Key> keys) {
            reads.add(List.copyOf(keys));
            if (failing) {
                return Map.of();
            }
            var hits = new LinkedHashMap<Key, Integer>();
            for (Key key : keys) {
                Integer value = entries.get(key);
                if (value != null) {
                    hits.put(key, value);
                }
            }
            return hits;
        }

        @Override
        public void putAll(Map<Key, Integer> freeBerths) {
            writes.add(Map.copyOf(freeBerths));
            if (!failing) {
                entries.putAll(freeBerths);
            }
        }
    }
}
