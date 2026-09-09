package io.tatkalrush.adapters.web;

import io.tatkalrush.application.usecases.SearchTrains;
import io.tatkalrush.application.usecases.SearchTrains.SearchCommand;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code API-1} (§11): {@code GET /api/v1/trains/search}.
 *
 * <h2>This endpoint is authenticated, which may look wrong</h2>
 *
 * <p>Real ticketing sites let anyone search. This one requires a token, because
 * §19.5 derives its harness sizing from the assumption that searches count against
 * FR-60's per-user cap: "P2's 2,000 rps needs ≥200 distinct users". Exempting
 * search would make that arithmetic wrong in the direction that hides a problem —
 * a run could look valid while nine tenths of its traffic bypassed the limiter
 * entirely.
 *
 * <h2>FR-14's label is part of the contract</h2>
 *
 * <p>{@code approximate} is not decoration. The count is an upper-bound estimate
 * read from a denormalised counter and cached for two seconds, and a client that
 * treats it as a reservation will be surprised during precisely the spike this
 * project exists to survive. Saying so in the payload is cheaper than saying so in
 * documentation nobody reads.
 */
@RestController
@Profile("!psp-sim")
@RequestMapping("/api/v1/trains")
public class SearchController {

    private final SearchTrains searchTrains;
    private final InstantSource clock;

    public SearchController(SearchTrains searchTrains, InstantSource clock) {
        this.searchTrains = searchTrains;
        this.clock = clock;
    }

    // ── response shape ──────────────────────────────────────────────────────

    /**
     * @param approximate FR-14, stated in every response
     * @param cache FR-15's effectiveness on this request. Present because the
     *     benchmark report claims the cache "collapses read amplification by orders
     *     of magnitude", and a claim like that should be readable from a single
     *     response rather than inferred from a Grafana panel.
     */
    public record SearchResponse(
            String from,
            String to,
            LocalDate date,
            boolean approximate,
            String note,
            CacheStats cache,
            List<TrainResponse> trains) {}

    public record CacheStats(int hits, int misses) {}

    /**
     * @param hot FR-49's designated hot trains, surfaced so a load profile can
     *     target one without hard-coding an id that the seed generator chose
     * @param fromSeq segment indices for this train, which differ per train even
     *     for the same city pair
     */
    public record TrainResponse(
            long scheduleId,
            String trainNumber,
            String trainName,
            boolean hot,
            LocalTime departsAt,
            LocalTime arrivesAt,
            BigDecimal distanceKm,
            int fromSeq,
            int toSeq,
            List<ClassResponse> classes) {}

    /**
     * @param availableBerths FR-13's estimate, or null where the pool has no
     *     readable Redis state. Null rather than 0, which would report a full train
     *     as sold out.
     * @param opensAt when a locked TATKAL pool unlocks (FR-29), else absent
     */
    public record ClassResponse(
            String travelClass,
            String quota,
            Integer availableBerths,
            int totalBerths,
            boolean stale,
            boolean bookable,
            Instant opensAt) {}

    // ── API-1 ───────────────────────────────────────────────────────────────

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam("date") String date,
            // FR-12's filter. Absent means every class, which is what a passenger
            // who has not decided yet is asking.
            @RequestParam(value = "class", required = false) String travelClass) {

        LocalDate journeyDate;
        try {
            journeyDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return ApiProblem.of(
                    ApiError.INVALID_REQUEST, "date must be ISO-8601 (yyyy-MM-dd), got " + date);
        }

        Optional<TravelClass> filter;
        try {
            filter =
                    travelClass == null || travelClass.isBlank()
                            ? Optional.empty()
                            : Optional.of(TravelClass.fromCode(travelClass));
        } catch (IllegalArgumentException e) {
            return ApiProblem.of(ApiError.INVALID_REQUEST, e.getMessage());
        }

        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return ApiProblem.of(ApiError.INVALID_REQUEST, "from and to are required");
        }
        if (from.equals(to)) {
            // Not merely empty: the query would find no row because it requires
            // from_stop.seq < to_stop.seq, and reporting "no trains" for a journey
            // that is not a journey sends the caller looking at timetables.
            return ApiProblem.of(ApiError.INVALID_REQUEST, "from and to are the same station");
        }

        var result =
                searchTrains.search(
                        new SearchCommand(from, to, journeyDate, filter, clock.instant()));

        return switch (result) {
            case SearchTrains.Result.UnknownStation unknown ->
                    // 400, not 404. The search itself is a perfectly good resource;
                    // it is the parameter that names nothing.
                    ApiProblem.of(
                            ApiError.INVALID_REQUEST,
                            "unknown station code(s): " + String.join(", ", unknown.codes()));

            case SearchTrains.Result.Found found ->
                    ResponseEntity.ok(body(from, to, journeyDate, found));
        };
    }

    private static SearchResponse body(
            String from, String to, LocalDate date, SearchTrains.Result.Found found) {

        return new SearchResponse(
                from,
                to,
                date,
                true,
                "FR-14: availability is an upper-bound estimate, cached for up to 2 s. "
                        + "Only a hold reserves a berth.",
                new CacheStats(found.cacheHits(), found.cacheMisses()),
                found.trains().stream().map(SearchController::train).toList());
    }

    private static TrainResponse train(SearchTrains.TrainAvailability train) {
        return new TrainResponse(
                train.scheduleId(),
                train.trainNumber(),
                train.trainName(),
                train.hot(),
                train.departsAt(),
                train.arrivesAt(),
                train.distanceKm(),
                train.range().fromSeq(),
                train.range().toSeq(),
                train.classes().stream().map(SearchController::travelClass).toList());
    }

    private static ClassResponse travelClass(SearchTrains.ClassAvailability availability) {
        return new ClassResponse(
                // code(), not name(): the wire carries "3A", the enum constant is
                // AC3, and a client sending back what it received must be able to
                // use it as the hold request's travelClass.
                availability.travelClass().code(),
                availability.quotaType().name(),
                availability.freeBerths(),
                availability.totalBerths(),
                availability.stale(),
                availability.bookable(),
                availability.opensAt());
    }
}
