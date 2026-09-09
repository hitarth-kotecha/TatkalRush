package io.tatkalrush.application.usecases;

import io.tatkalrush.application.ports.AvailabilityCache;
import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.application.ports.TrainSearchQuery;
import io.tatkalrush.application.ports.TrainSearchQuery.PoolOnRoute;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code API-1}: which trains serve this journey, and roughly how full are they
 * (FR-12 … FR-15).
 *
 * <h2>Approximate is the specification, not a shortcoming</h2>
 *
 * <p>FR-13 answers with {@code min(free_count[i])} over the requested segments,
 * read from a denormalised per-segment counter and cached for two seconds. It may
 * be wrong. FR-14 says so explicitly and instructs the reviewer to treat any
 * attempt to make search strongly consistent as a <b>defect</b>.
 *
 * <p>The reason is load. Search runs at roughly nine times the rate of booking
 * (§19's P2 is 90% search), so an exact count would put nine tenths of the
 * system's traffic onto the same contended structure the allocator needs to be
 * correct — search would slow booking down, and §9.4's comparison of the two
 * allocators would end up measuring the search path.
 *
 * <p>A result is therefore never a reservation. "It said four seats and my hold
 * failed" is this system working during a spike, and {@code SEAT_UNAVAILABLE} is
 * classified by FR-51 as a correct outcome for exactly that reason.
 *
 * <h2>The minimum, never the average</h2>
 *
 * <p>A journey needs the <em>same</em> berth for its whole length. Forty berths
 * free on three segments and one free on the fourth is one available berth, not
 * thirty-one, and averaging would advertise inventory that cannot be sold.
 *
 * <h2>Two round trips, not two per pool</h2>
 *
 * <p>One SQL query resolves every train, class and quota on the route; one batched
 * cache read covers all of them; only the misses reach Redis individually. A busy
 * route resolves to a dozen or more pools, and a dozen sequential round trips is
 * the whole of NFR-5's 50 ms budget.
 */
public final class SearchTrains {

    private final TrainSearchQuery routes;
    private final SeatAllocator allocator;
    private final AvailabilityCache cache;

    public SearchTrains(
            TrainSearchQuery routes, SeatAllocator allocator, AvailabilityCache cache) {
        this.routes = routes;
        this.allocator = allocator;
        this.cache = cache;
    }

    /**
     * @param travelClass empty means every class (FR-12's {@code &class} is a
     *     filter)
     * @param now FR-31's injected clock, which decides whether TATKAL is open. Read
     *     from the caller rather than from {@code Instant.now()} so a test can put
     *     the window on either side of the boundary without waiting for 10 AM.
     */
    public record SearchCommand(
            String fromStationCode,
            String toStationCode,
            LocalDate journeyDate,
            Optional<TravelClass> travelClass,
            Instant now) {}

    public sealed interface Result {

        /**
         * @param trains may be empty — a route nobody runs on the requested date is
         *     a valid answer to a valid question, not an error
         * @param cacheHits FR-15's effectiveness, reported rather than logged: the
         *     benchmark report claims the cache "collapses read amplification", and
         *     a claim like that needs a number the run itself produced
         */
        record Found(List<TrainAvailability> trains, int cacheHits, int cacheMisses)
                implements Result {}

        /**
         * At least one station code is not in {@code stations}.
         *
         * <p>Distinguished from an empty result because they are the same zero rows
         * and completely different problems. Telling a client "no trains" when it
         * asked about a station that does not exist sends it hunting the wrong
         * thing.
         */
        record UnknownStation(List<String> codes) implements Result {}
    }

    /**
     * @param range this train's segment indices for the requested journey. Included
     *     because it differs per train — two trains serving the same city pair stop
     *     at different places, so the same journey is {@code [0,7)} on one and
     *     {@code [2,4)} on another.
     */
    public record TrainAvailability(
            long scheduleId,
            String trainNumber,
            String trainName,
            boolean hot,
            SegmentRange range,
            LocalTime departsAt,
            LocalTime arrivesAt,
            BigDecimal distanceKm,
            List<ClassAvailability> classes) {}

    /**
     * @param freeBerths FR-13's upper-bound estimate, or {@code null} when this
     *     pool has no Redis state to read. Null rather than zero: zero is a
     *     legitimate answer meaning sold out, and manufacturing one from a missing
     *     key would advertise a full train as sold out. The state is reachable —
     *     between a pool's creation in Postgres and its initialisation in Redis,
     *     and after chaos scenario C2's {@code FLUSHALL} until §13.4's rebuild
     *     completes. INV-8 reports the same condition from the other direction.
     * @param stale served from cache, so up to FR-15's TTL out of date. Always
     *     {@code false} when {@code freeBerths} is null.
     * @param bookable whether a hold could succeed at all — false once charted
     *     (FR-42) or while TATKAL is locked (FR-28)
     * @param opensAt when a locked TATKAL pool unlocks, else null. FR-29 requires
     *     the instant, not merely the fact: a client that knows when to come back
     *     waits, and one that does not polls, which is the herd FR-30 exists to
     *     avoid.
     */
    public record ClassAvailability(
            TravelClass travelClass,
            QuotaType quotaType,
            Integer freeBerths,
            int totalBerths,
            boolean stale,
            boolean bookable,
            Instant opensAt) {}

    public Result search(SearchCommand command) {
        var query =
                new TrainSearchQuery.Query(
                        command.fromStationCode(),
                        command.toStationCode(),
                        command.journeyDate(),
                        command.travelClass());

        List<PoolOnRoute> pools = routes.poolsOnRoute(query);

        if (pools.isEmpty()) {
            // Only now, on the path that is already doing no work. Charging every
            // populated search for this check would be a wasted query on the
            // hottest endpoint in the system.
            List<String> asked = List.of(command.fromStationCode(), command.toStationCode());
            List<String> known = routes.stationsExist(asked);
            List<String> unknown = asked.stream().filter(code -> !known.contains(code)).toList();
            if (!unknown.isEmpty()) {
                return new Result.UnknownStation(unknown);
            }
            return new Result.Found(List.of(), 0, 0);
        }

        var counts = freeBerths(pools);
        return new Result.Found(
                assemble(pools, counts, command.now()), counts.hits(), counts.misses());
    }

    /**
     * Free-berth counts for every pool: cache first, allocator for the misses.
     *
     * @param byKey the answers found, keyed as FR-15 keys them. A pool absent from
     *     this map had no cached value <em>and</em> no readable Redis state.
     * @param fresh the subset computed on this request. Carried rather than derived
     *     because "stale" must mean precisely one thing — served from FR-15's cache
     *     — and not "old" in any looser sense a reader might assume when the
     *     benchmark report quotes the hit rate.
     */
    private record Counts(
            Map<AvailabilityCache.Key, Integer> byKey,
            Set<AvailabilityCache.Key> fresh,
            int hits,
            int misses) {}

    private Counts freeBerths(List<PoolOnRoute> pools) {
        List<AvailabilityCache.Key> keys =
                pools.stream()
                        .map(p -> new AvailabilityCache.Key(p.pool(), p.range()))
                        .distinct()
                        .toList();

        Map<AvailabilityCache.Key, Integer> cached = cache.getAll(keys);

        var resolved = new HashMap<>(cached);
        var toCache = new LinkedHashMap<AvailabilityCache.Key, Integer>();

        for (AvailabilityCache.Key key : keys) {
            if (resolved.containsKey(key)) {
                continue;
            }
            try {
                int free = allocator.availability(key.pool(), key.range()).freeBerths();
                resolved.put(key, free);
                toCache.put(key, free);
            } catch (RuntimeException e) {
                // One unprovisioned pool must not fail a search that has a correct
                // answer for every other train on the route. Left out of `resolved`
                // so it surfaces as a null count rather than a fabricated zero.
                //
                // Deliberately not logged, and not because logging is unavailable
                // here - `application/` has no slf4j and that is the point (§8.2).
                // The condition is already reported in two places that a test can
                // assert on: the response carries a null count, and INV-8 reports
                // the same pool from the other direction. IntegrityAlarm's javadoc
                // makes the argument in full - a log.error is not a check.
                continue;
            }
        }

        if (!toCache.isEmpty()) {
            cache.putAll(toCache);
        }

        return new Counts(
                resolved, toCache.keySet(), cached.size(), keys.size() - cached.size());
    }

    private List<TrainAvailability> assemble(
            List<PoolOnRoute> pools, Counts counts, Instant now) {

        // Insertion-ordered: the query orders by train number, class then quota, and
        // a search that returns trains in a different order on every call is
        // needlessly hard to diff between two benchmark runs.
        var byTrain = new LinkedHashMap<Long, List<PoolOnRoute>>();
        for (PoolOnRoute pool : pools) {
            byTrain.computeIfAbsent(pool.pool().scheduleId(), id -> new ArrayList<>()).add(pool);
        }

        var trains = new ArrayList<TrainAvailability>(byTrain.size());
        for (List<PoolOnRoute> group : byTrain.values()) {
            PoolOnRoute first = group.getFirst();
            trains.add(
                    new TrainAvailability(
                            first.pool().scheduleId(),
                            first.trainNumber(),
                            first.trainName(),
                            first.hot(),
                            first.range(),
                            first.departsAt(),
                            first.arrivesAt(),
                            first.distanceKm(),
                            group.stream().map(p -> classOf(p, counts, now)).toList()));
        }
        return trains;
    }

    private static ClassAvailability classOf(PoolOnRoute pool, Counts counts, Instant now) {
        PoolKey key = pool.pool();
        Integer free = counts.byKey().get(new AvailabilityCache.Key(key, pool.range()));

        boolean windowOpen =
                TatkalWindow.isPoolOpen(
                        key.quotaType(), pool.journeyDate(), key.travelClass(), now);

        // Computed from the journey date, exactly as HoldSeats computes it, rather
        // than read from quota_pools.opens_at. Two sources for one rule is how a
        // search says "opens at 10:00" while a hold says "not yet" - and FR-30
        // makes the clock the only input, so there is nothing to store.
        Instant opensAt =
                windowOpen
                        ? null
                        : TatkalWindow.opensAt(pool.journeyDate(), key.travelClass());

        return new ClassAvailability(
                key.travelClass(),
                key.quotaType(),
                free,
                pool.totalBerths(),
                // Stale only where a number came from cache. A miss was computed
                // just now, and a null was not computed at all.
                free != null && !counts.fresh().contains(new AvailabilityCache.Key(key, pool.range())),
                windowOpen && !pool.chartPrepared(),
                opensAt);
    }
}
