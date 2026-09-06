package io.tatkalrush.adapters.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.tatkalrush.application.ports.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.InstantSource;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FR-60 at the edge.
 *
 * <p>Ordered <b>after</b> {@code JwtAuthFilter}, because the limit is per user and
 * the user only exists once the token has been verified. Limiting by IP instead
 * would throttle §19's whole harness as one caller — every virtual user arrives
 * from the same host.
 *
 * <h2>The counter matters as much as the rejection</h2>
 *
 * <p>§19.5: {@code RATE_LIMITED} "reflects harness configuration, not system state,
 * and its presence <b>voids a benchmark run</b> rather than counting against it".
 * A run cannot be voided by something nobody recorded, so
 * {@code rate_limited_total} is what makes that rule enforceable — the report
 * generator reads it and refuses to publish a run where it is non-zero.
 */
@Component
// After authentication (HIGHEST + 10), because the key is the authenticated user.
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Paths outside the limit.
     *
     * <p>{@code /actuator} because Prometheus scrapes it every few seconds and
     * throttling metrics collection during a spike removes the observability
     * exactly when it is needed. The webhook because the PSP is not a user and has
     * no id to key on — and because §12's simulator delivering a burst of settled
     * payments is the system working, not a client misbehaving.
     */
    private static final List<String> UNLIMITED =
            List.of("/actuator", "/api/v1/auth/", "/api/v1/payments/webhook", "/psp/");

    private final RateLimiter limiter;
    private final InstantSource clock;
    private final Counter limited;

    public RateLimitFilter(RateLimiter limiter, InstantSource clock, MeterRegistry meters) {
        this.limiter = limiter;
        this.clock = clock;
        this.limited =
                Counter.builder("rate_limited_total")
                        .description(
                                "FR-60 rejections. Non-zero VOIDS a benchmark run (§19.5) - it"
                                    + " means the harness was under-provisioned with users, not"
                                    + " that the system was overloaded.")
                        .register(meters);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return UNLIMITED.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {

        if (!RequestContext.USER_ID.isBound()) {
            // Unauthenticated requests never reach here - JwtAuthFilter runs
            // first and rejects them. If one does, letting it through is right:
            // it means the filter order changed, and a rate limiter is not the
            // place to discover that.
            chain.doFilter(request, response);
            return;
        }

        var decision = limiter.check(RequestContext.userId(), clock.instant());

        if (decision instanceof RateLimiter.Decision.Limited limitedDecision) {
            this.limited.increment();

            response.setStatus(ApiError.RATE_LIMITED.status().value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            // Seconds, per RFC 9110. A client told only "no" retries immediately
            // and makes the condition worse.
            response.setHeader(
                    "Retry-After",
                    String.valueOf(Math.max(1, limitedDecision.retryAfter().toSeconds())));
            response.getWriter()
                    .write(
                            """
                            {"type":"%s","title":"RATE_LIMITED","status":429,\
                            "code":"RATE_LIMITED",\
                            "detail":"FR-60 allows 10 requests per second per user",\
                            "correlationId":"%s"}"""
                                    .formatted(
                                            ApiError.RATE_LIMITED.typeUri(),
                                            RequestContext.correlationId()));
            return;
        }

        chain.doFilter(request, response);
    }
}
