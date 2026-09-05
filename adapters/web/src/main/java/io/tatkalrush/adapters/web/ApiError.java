package io.tatkalrush.adapters.web;

import org.springframework.http.HttpStatus;

/**
 * §11.2's error codes.
 *
 * <h2>The code is the identity, not the status</h2>
 *
 * <p>Three of these return {@code 429} and three return {@code 409}. §11.2 says it
 * outright: "k6 thresholds and Grafana panels must split on the error <b>code</b>,
 * not the status, or admission pressure and rate-limit rejection blur into one
 * series on the demo dashboard." So the code travels as a named field in the
 * response body, and nothing downstream is expected to infer it from the status
 * line.
 *
 * <h2>Two of these are not errors</h2>
 *
 * <p>FR-51: {@code SEAT_UNAVAILABLE} and {@code QUOTA_EXHAUSTED} are <b>correct
 * outcomes</b>. A train with no berths left answering "no berths left" is the
 * system working. They must be excluded from NFR-7's error budget and counted
 * separately, and {@link #isCorrectOutcome()} is what lets the metrics do that
 * structurally rather than by someone remembering to.
 *
 * <p>{@code RATE_LIMITED} is deliberately <em>not</em> in that set and is handled
 * differently again: §19.5 says it reflects harness configuration rather than
 * system state, and its presence <b>voids a benchmark run</b> rather than counting
 * against it.
 */
public enum ApiError {

    /** No berth free for that segment range. A correct outcome (FR-51). */
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, true),

    /** CNF + RAC + WL all full. A correct outcome (FR-51). */
    QUOTA_EXHAUSTED(HttpStatus.CONFLICT, true),

    /** The Tatkal window has not opened (FR-29). Carries the opening instant. */
    QUOTA_LOCKED(HttpStatus.CONFLICT, false),

    /** The hold TTL elapsed. 410 Gone, because the thing genuinely no longer exists. */
    HOLD_EXPIRED(HttpStatus.GONE, false),

    /** Partition in queued mode; obtain a token (FR-32). */
    QUEUE_REQUIRED(HttpStatus.TOO_MANY_REQUESTS, false),

    /** Projected wait exceeds the horizon; no token issued (FR-35a). */
    QUEUE_FULL(HttpStatus.TOO_MANY_REQUESTS, false),

    /** Token valid, not yet admitted. 425 Too Early. */
    QUEUE_NOT_ADMITTED(HttpStatus.valueOf(425), false),

    /** FR-60's per-user cap. See the class comment: this one voids a run. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, false),

    /** Same key, different body (FR-19). A client bug, not a retry. */
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, false),

    /** Partition owner replaying, or a reply timed out (Strategy B). */
    RETRY_LATER(HttpStatus.SERVICE_UNAVAILABLE, false),

    /** Booking is closed for this schedule (FR-42). */
    CHART_PREPARED(HttpStatus.CONFLICT, false),

    /** FR-20's cap on concurrent holds per caller. */
    TOO_MANY_HOLDS(HttpStatus.TOO_MANY_REQUESTS, false),

    /** Missing, malformed or expired credentials (FR-59). */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, false),

    /** The request could not be understood. */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, false),

    /** No such booking, schedule or pool. */
    NOT_FOUND(HttpStatus.NOT_FOUND, false);

    private final HttpStatus status;
    private final boolean correctOutcome;

    ApiError(HttpStatus status, boolean correctOutcome) {
        this.status = status;
        this.correctOutcome = correctOutcome;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * Whether this is FR-51's "correct outcome, not an error".
     *
     * <p>Excluded from NFR-7's error budget. Conflating these with real failures is
     * called out in §11.2 as "a common and revealing mistake" — a benchmark that
     * counts sold-out trains as errors reports a system failing under load when
     * what it is doing is refusing to overbook.
     */
    public boolean isCorrectOutcome() {
        return correctOutcome;
    }

    /** The RFC 7807 {@code type} URI. Stable, and a place to hang documentation. */
    public String typeUri() {
        return "https://tatkalrush.io/problems/" + name().toLowerCase().replace('_', '-');
    }
}
