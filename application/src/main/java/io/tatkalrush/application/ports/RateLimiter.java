package io.tatkalrush.application.ports;

import java.time.Duration;
import java.time.Instant;

/**
 * FR-60: 10 requests per second, per user.
 *
 * <h2>Its success condition during a benchmark is silence</h2>
 *
 * <p>§19.5 is unusual and worth reading twice: {@code RATE_LIMITED} "reflects
 * harness configuration, not system state", and its presence <b>voids a benchmark
 * run</b> rather than counting against it. §19's profiles map one k6 virtual user
 * to one distinct synthetic user (FR-69) precisely so this never binds — at P1's
 * 5,000 VUs that is 1:1, and a limiter that fired would mean the harness was
 * under-provisioned with users and the numbers describe the harness.
 *
 * <p>So FR-60 is tested by "a dedicated integration test with a single user and a
 * tight loop — never via a load profile". A limiter exercised by a benchmark is a
 * benchmark measuring the wrong thing.
 *
 * <h2>Keyed by user, which means it runs after authentication</h2>
 *
 * <p>Not by IP. §19's virtual users all arrive from one host, so an IP limit would
 * throttle the entire harness as though it were one caller — and the user id only
 * exists once the token has been verified (FR-59).
 */
public interface RateLimiter {

    sealed interface Decision {

        /** @param remaining an estimate; the window is a sliding approximation */
        record Allowed(int remaining) implements Decision {}

        /**
         * @param retryAfter how long until the window has room. Returned so the
         *     429 can carry a {@code Retry-After} header: a client told only "no"
         *     retries immediately and makes the condition worse.
         */
        record Limited(Duration retryAfter) implements Decision {}
    }

    /**
     * Records a request and says whether it may proceed.
     *
     * <p><b>Implementations must fail open.</b> If the backing store is
     * unreachable, return {@link Decision.Allowed}. A limiter that fails closed
     * turns a Redis blip into a total outage — and chaos scenario C2 flushes Redis
     * during P2, so failing closed would make C2 a measurement of this class rather
     * than of the system, and would void every C2 run under §19.5 automatically.
     *
     * <p>The general rule cuts differently by what is being guarded: an
     * authorisation check fails <em>closed</em>, because admitting the wrong person
     * is worse than admitting nobody. A rate limiter guards capacity, and denying
     * everyone to enforce a limit is the outage it exists to prevent.
     */
    Decision check(long userId, Instant now);
}
