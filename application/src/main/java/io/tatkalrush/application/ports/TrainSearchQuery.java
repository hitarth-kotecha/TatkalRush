package io.tatkalrush.application.ports;

import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Read model for {@code API-1} (FR-12).
 *
 * <p>Separate from {@link ScheduleQuery} because the questions are different
 * shapes. {@code ScheduleQuery} answers "tell me everything about <em>this</em>
 * pool", which the hold path asks once per request with the pool already in hand.
 * Search asks "which pools exist on a route I can only describe by two station
 * codes" — and must answer it for every train, every class and every quota in one
 * query, because the alternative is an N+1 on the endpoint that carries nine
 * tenths of P2's load.
 *
 * <h2>The range differs per train, which is why it comes back per row</h2>
 *
 * <p>Two trains both serving Delhi to Mumbai may stop at different places along
 * the way, so the same journey is {@code [0,7)} on one and {@code [2,4)} on
 * another. A caller cannot resolve the range once and reuse it; the segment
 * indices are a property of the route, not of the journey.
 */
public interface TrainSearchQuery {

    /**
     * @param travelClass empty means every class. FR-12's {@code &class} parameter
     *     is a filter, not a requirement — a passenger who has not decided yet is
     *     the normal case, and forcing a choice would make the endpoint useless as
     *     a first screen.
     */
    record Query(
            String fromStationCode,
            String toStationCode,
            LocalDate journeyDate,
            Optional<TravelClass> travelClass) {

        public Query {
            if (fromStationCode == null || fromStationCode.isBlank()) {
                throw new IllegalArgumentException("fromStationCode is required");
            }
            if (toStationCode == null || toStationCode.isBlank()) {
                throw new IllegalArgumentException("toStationCode is required");
            }
            if (journeyDate == null) {
                throw new IllegalArgumentException("journeyDate is required");
            }
            if (travelClass == null) {
                throw new IllegalArgumentException(
                        "travelClass must be Optional.empty(), never null");
            }
        }
    }

    /**
     * One quota pool, on one train, resolved against the requested station pair.
     *
     * <p>The unit is the <b>pool</b>, not the train: GENERAL and TATKAL over the
     * same physical berths are two rows here, because FR-10 makes their
     * availability different by definition. Grouping them into trains is the use
     * case's job, done once the availability numbers are attached.
     *
     * @param segmentCount segments on this train's route, which the allocator needs
     *     to read the free-count blob correctly
     * @param departsAt scheduled departure from the <em>requested</em> origin, not
     *     from the train's own origin
     * @param arrivesAt scheduled arrival at the requested destination
     * @param distanceKm distance for this leg only, a subtraction of two cumulative
     *     readings. {@code BigDecimal} because the column is {@code NUMERIC(7,2)}
     *     and FR-67 prices from it.
     * @param chartPrepared once charted, booking is closed (FR-42) — reported so a
     *     search does not advertise berths no hold can take
     */
    record PoolOnRoute(
            PoolKey pool,
            String trainNumber,
            String trainName,
            boolean hot,
            SegmentRange range,
            int segmentCount,
            int totalBerths,
            LocalDate journeyDate,
            Instant departureAt,
            LocalTime departsAt,
            LocalTime arrivesAt,
            BigDecimal distanceKm,
            boolean chartPrepared) {}

    /**
     * Every pool bookable for this journey, ordered by train number then class then
     * quota, so the response is stable across runs.
     *
     * <p>Empty for three different reasons — no such station, no train between
     * them, or no train on that date — and this port deliberately does not
     * distinguish them. See {@link #stationsExist}.
     */
    List<PoolOnRoute> poolsOnRoute(Query query);

    /**
     * Which of these station codes exist.
     *
     * <p>Exists so that an empty search result can say <em>why</em> without every
     * populated search paying for the check. A typo in a station code and a route
     * nobody runs are the same zero rows, and telling a client "no trains" when it
     * asked about a station that does not exist sends it looking for the wrong
     * problem.
     *
     * <p>Called only when {@link #poolsOnRoute} returned nothing, so its cost falls
     * on the path that is already doing no work.
     *
     * @return the subset of {@code codes} present in {@code stations}
     */
    List<String> stationsExist(List<String> codes);
}
