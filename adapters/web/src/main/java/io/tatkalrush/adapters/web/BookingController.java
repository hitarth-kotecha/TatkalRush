package io.tatkalrush.adapters.web;

import io.tatkalrush.application.ports.ScheduleQuery;
import io.tatkalrush.application.usecases.HoldSeats;
import io.tatkalrush.application.usecases.HoldSeats.HoldSeatsCommand;
import io.tatkalrush.application.usecases.InitiatePayment;
import io.tatkalrush.domain.booking.Passenger;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TravelClass;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code API-4} and {@code API-5} (§11).
 *
 * <p>The controller's job is translation, and there are three of them:
 *
 * <ol>
 *   <li><b>Station codes to segment indices.</b> §11.1's request carries
 *       {@code fromStationCode} and {@code toStationCode}, because that is what a
 *       passenger knows; the allocator addresses a 64-bit mask by segment.
 *   <li><b>Request body to idempotency hash.</b> See {@link #requestHash}.
 *   <li><b>Sealed result to HTTP.</b> Every {@code HoldSeats.Result} maps to one
 *       §11.2 code, and the compiler checks the mapping is total — adding an
 *       outcome without deciding its status will not compile.
 * </ol>
 *
 * <p>What the controller does <b>not</b> do is decide anything. The ordering that
 * makes FR-19 and FR-51 work lives in the use case; if a rule appears here that is
 * not about HTTP, it is in the wrong file.
 */
@RestController
// §8.3 runs one image in three roles; this one belongs to app-1 and app-2.
@Profile("!psp-sim")
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final HoldSeats holdSeats;
    private final InitiatePayment initiatePayment;
    private final ScheduleQuery schedules;
    private final InstantSource clock;

    public BookingController(
            HoldSeats holdSeats,
            InitiatePayment initiatePayment,
            ScheduleQuery schedules,
            InstantSource clock) {
        this.holdSeats = holdSeats;
        this.initiatePayment = initiatePayment;
        this.schedules = schedules;
        this.clock = clock;
    }

    // ── API-4 ───────────────────────────────────────────────────────────────

    /**
     * §11.1's hold request.
     *
     * <p><b>There is no {@code userId} field, and that is FR-59's enforcement.</b>
     * The requirement says the id is taken from the JWT and "never accepted from
     * the request body". A field with a validation rule is a field somebody
     * eventually trusts; a field that does not exist cannot be populated.
     */
    public record HoldRequest(
            Long scheduleId,
            String travelClass,
            String quotaType,
            String fromStationCode,
            String toStationCode,
            List<PassengerRequest> passengers) {}

    public record PassengerRequest(String name, Integer age, String gender) {}

    @PostMapping("/hold")
    public ResponseEntity<?> hold(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody HoldRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // FR-19 makes the header mandatory, and generating one here would
            // defeat it entirely: the client's retry would carry a different key
            // and allocate a second set of berths.
            return ApiProblem.of(
                    ApiError.INVALID_REQUEST, "the Idempotency-Key header is required (FR-19)");
        }

        PoolKey pool;
        List<Passenger> passengers;
        try {
            pool = toPoolKey(request);
            passengers = toPassengers(request);
        } catch (IllegalArgumentException e) {
            return ApiProblem.of(ApiError.INVALID_REQUEST, e.getMessage());
        }

        var range =
                schedules.resolveRange(
                        request.scheduleId(), request.fromStationCode(), request.toStationCode());
        if (range.isEmpty()) {
            return ApiProblem.of(
                    ApiError.INVALID_REQUEST,
                    "%s to %s is not a journey on schedule %d"
                            .formatted(
                                    request.fromStationCode(),
                                    request.toStationCode(),
                                    request.scheduleId()));
        }

        HoldSeatsCommand command;
        try {
            command =
                    new HoldSeatsCommand(
                            pool,
                            range.get(),
                            passengers,
                            // FR-59: from the token, never from the body.
                            RequestContext.userId(),
                            idempotencyKey,
                            requestHash(pool, request),
                            clock.instant());
        } catch (IllegalArgumentException e) {
            return ApiProblem.of(ApiError.INVALID_REQUEST, e.getMessage());
        }

        return toResponse(holdSeats.handle(command));
    }

    /**
     * The fingerprint FR-19 compares when the same key arrives twice.
     *
     * <p>Computed over the <b>parsed fields</b>, not the raw body — the opposite of
     * the webhook signature two files away, and for the opposite reason. A
     * signature proves where a message came from, so the exact bytes are the thing
     * being attested. This hash proves two requests mean the same thing, so a
     * client that reformats its JSON, reorders its keys, or changes its whitespace
     * between attempts is sending the same request and must not be refused with
     * {@code IDEMPOTENCY_KEY_REUSED} for a retry that was entirely legitimate.
     *
     * <p>Passenger details are included: the same key with a different passenger
     * list is a different booking, and answering it with the first one's berths
     * would confirm the wrong people onto a train.
     */
    private static String requestHash(PoolKey pool, HoldRequest request) {
        var canonical = new StringBuilder();
        canonical
                .append(pool.scheduleId())
                .append('|')
                .append(pool.travelClass().code())
                .append('|')
                .append(pool.quotaType())
                .append('|')
                .append(request.fromStationCode())
                .append('|')
                .append(request.toStationCode());

        for (PassengerRequest passenger : request.passengers()) {
            canonical
                    .append('|')
                    .append(passenger.name())
                    .append(':')
                    .append(passenger.age())
                    .append(':')
                    .append(passenger.gender());
        }

        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Every outcome, mapped. The switch is exhaustive over a sealed interface, so a
     * new {@code Result} that nobody assigned a status to is a compile error rather
     * than a 500 discovered in a load run.
     */
    private ResponseEntity<?> toResponse(HoldSeats.Result result) {
        return switch (result) {
            case HoldSeats.Result.Held held -> ResponseEntity.status(201).body(heldBody(held));

            case HoldSeats.Result.DuplicateRequest duplicate ->
                    // FR-19: 200 with the CURRENT representation, re-rendered from
                    // booking state. Never a stored copy of the original response,
                    // which at t=300 s would still claim a hold that died at 120.
                    ResponseEntity.ok(replayBody(duplicate));

            case HoldSeats.Result.SeatUnavailable unavailable ->
                    ApiProblem.of(
                            ApiError.SEAT_UNAVAILABLE,
                            "%d berths free, %d requested"
                                    .formatted(unavailable.available(), unavailable.requested()));

            case HoldSeats.Result.QuotaLocked locked ->
                    ApiProblem.of(
                            ApiError.QUOTA_LOCKED,
                            "the Tatkal window has not opened",
                            // FR-29: the instant, not merely the fact. A client
                            // that knows when to return waits; one that does not
                            // polls, which is the herd FR-30 exists to avoid.
                            Map.of("opensAt", locked.opensAt().toString()));

            case HoldSeats.Result.IdempotencyKeyReused reused ->
                    ApiProblem.of(
                            ApiError.IDEMPOTENCY_KEY_REUSED,
                            "this Idempotency-Key was used for a different request");

            case HoldSeats.Result.RetryLater ignored ->
                    ApiProblem.of(ApiError.RETRY_LATER, "retry with the same Idempotency-Key");

            case HoldSeats.Result.TooManyHolds tooMany ->
                    ApiProblem.of(
                            ApiError.TOO_MANY_HOLDS,
                            "%d holds open, limit %d"
                                    .formatted(tooMany.active(), tooMany.limit()));

            case HoldSeats.Result.ChartPrepared ignored ->
                    ApiProblem.of(ApiError.CHART_PREPARED, "booking is closed for this schedule");

            case HoldSeats.Result.UnknownPool pool ->
                    ApiProblem.of(ApiError.NOT_FOUND, "no such pool: " + pool.pool());

            case HoldSeats.Result.InvalidRange invalid ->
                    ApiProblem.of(
                            ApiError.INVALID_REQUEST,
                            "segment %d is past the end of a %d-segment route"
                                    .formatted(
                                            invalid.range().toSeq(), invalid.segmentCount()));
        };
    }

    private Map<String, Object> heldBody(HoldSeats.Result.Held held) {
        var body = new LinkedHashMap<String, Object>();
        body.put("bookingId", held.bookingId());
        body.put("bookingClass", "CNF");
        body.put("status", "HELD");
        body.put("expiresAt", held.expiresAt().toString());
        body.put("farePaise", held.farePaise());
        body.put("allocations", allocations(held.berthIds()));
        return body;
    }

    private Map<String, Object> replayBody(HoldSeats.Result.DuplicateRequest duplicate) {
        var booking = duplicate.booking();
        var body = new LinkedHashMap<String, Object>();
        body.put("bookingId", booking.id());
        body.put("bookingClass", "CNF");
        // §11.1: "The response carries status because an idempotency replay returns
        // the CURRENT representation, which may be EXPIRED or CONFIRMED with a PNR
        // rather than HELD."
        body.put("status", booking.status().name());
        booking.pnr().ifPresent(pnr -> body.put("pnr", pnr));
        booking.holdExpiresAt().ifPresent(at -> body.put("expiresAt", at.toString()));
        body.put("farePaise", booking.farePaise());
        body.put("allocations", allocations(booking.berthIds()));
        return body;
    }

    private List<Map<String, Object>> allocations(List<Long> berthIds) {
        return schedules.describeBerths(berthIds).stream()
                .map(
                        berth ->
                                Map.<String, Object>of(
                                        "coach", berth.coachCode(),
                                        "berth", berth.ordinal(),
                                        "berthType", berth.berthType()))
                .toList();
    }

    // ── API-5 ───────────────────────────────────────────────────────────────

    @PostMapping("/{bookingId}/pay")
    public ResponseEntity<?> pay(@PathVariable("bookingId") long bookingId) {
        // No Idempotency-Key. HELD -> PAYMENT_PENDING is a compare-and-set, so the
        // state machine already admits exactly one caller and a second attempt
        // returns the payment that exists. A header here would be a second thing
        // to keep consistent and no extra guarantee.
        var result = initiatePayment.initiate(bookingId, clock.instant());

        return switch (result) {
            case InitiatePayment.Result.Initiated initiated ->
                    ResponseEntity.accepted()
                            .body(
                                    Map.of(
                                            "bookingId", initiated.bookingId(),
                                            "paymentReference", initiated.reference(),
                                            "amountPaise", initiated.amountPaise(),
                                            "status", "PAYMENT_PENDING"));

            case InitiatePayment.Result.AlreadyInitiated already ->
                    ResponseEntity.ok(
                            Map.of(
                                    "bookingId", already.bookingId(),
                                    "paymentReference", already.reference(),
                                    "amountPaise", already.amountPaise(),
                                    "status", "PAYMENT_PENDING"));

            case InitiatePayment.Result.Declined declined ->
                    ResponseEntity.status(402)
                            .body(
                                    Map.of(
                                            "bookingId", declined.bookingId(),
                                            "status", "FAILED",
                                            "reason", declined.reason()));

            case InitiatePayment.Result.OutcomeUnknown unknown ->
                    // 202, not 5xx. The charge may have landed; the client should
                    // poll rather than retry, because a retry with a fresh
                    // reference would risk a second charge.
                    ResponseEntity.accepted()
                            .body(
                                    Map.of(
                                            "bookingId", unknown.bookingId(),
                                            "paymentReference", unknown.reference(),
                                            "status", "PAYMENT_PENDING",
                                            "detail",
                                                    "the gateway did not answer; poll for the outcome"));

            case InitiatePayment.Result.HoldExpired expired ->
                    ApiProblem.of(
                            ApiError.HOLD_EXPIRED,
                            "the hold lapsed at " + expired.expiredAt());

            case InitiatePayment.Result.NotPayable notPayable ->
                    ApiProblem.of(
                            ApiError.INVALID_REQUEST,
                            "a booking in " + notPayable.status() + " cannot be paid for");

            case InitiatePayment.Result.UnknownBooking ignored ->
                    ApiProblem.of(ApiError.NOT_FOUND, "no such booking: " + bookingId);
        };
    }

    // ── request translation ─────────────────────────────────────────────────

    private static PoolKey toPoolKey(HoldRequest request) {
        if (request.scheduleId() == null) {
            throw new IllegalArgumentException("scheduleId is required");
        }
        if (request.fromStationCode() == null || request.toStationCode() == null) {
            throw new IllegalArgumentException(
                    "fromStationCode and toStationCode are required");
        }
        return new PoolKey(
                request.scheduleId(),
                TravelClass.fromCode(request.travelClass()),
                QuotaType.valueOf(request.quotaType()));
    }

    private static List<Passenger> toPassengers(HoldRequest request) {
        if (request.passengers() == null || request.passengers().isEmpty()) {
            throw new IllegalArgumentException("at least one passenger is required");
        }
        return request.passengers().stream()
                .map(
                        p -> {
                            if (p.age() == null) {
                                throw new IllegalArgumentException(
                                        "age is required for passenger " + p.name());
                            }
                            return new Passenger(
                                    p.name(), p.age(), Passenger.Gender.valueOf(p.gender()));
                        })
                .toList();
    }
}
