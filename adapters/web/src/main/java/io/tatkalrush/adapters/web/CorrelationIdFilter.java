package io.tatkalrush.adapters.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the correlation id for the lifetime of one request (SDD §15.3).
 *
 * <p>Runs first, before anything that might log, so that no line in a request's
 * trace is missing its id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    /** MDC key. Appears as a field on every structured log line. */
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = generateId();
        }

        // Echoed back so a k6 run or a curl can quote the id of a request that
        // misbehaved, instead of hunting for it by timestamp.
        response.setHeader(HEADER, correlationId);

        // Bound in BOTH places, deliberately, and the duplication is worth
        // explaining rather than hiding:
        //
        //   ScopedValue is the source of truth for application code (§8.5). It
        //   is immutable, unbinds automatically, and is inherited by
        //   StructuredTaskScope forks.
        //
        //   MDC is what actually gets the id onto each log line, and it is
        //   ThreadLocal-based. That is the mechanism §8.5 avoids for request
        //   context in general — but the objection there is scale: thousands of
        //   ThreadLocal entries holding request state. One short string per
        //   in-flight request is a different proposition, and the alternative is
        //   logs that cannot be correlated at all, which defeats §15.3.
        //
        //   MDC is cleared in the finally block. ScopedValue would not need it;
        //   ThreadLocal does, and that asymmetry is exactly why the rest of the
        //   codebase uses the former.
        MDC.put(MDC_KEY, correlationId);
        try {
            ScopedValue.where(RequestContext.CORRELATION_ID, correlationId)
                    .call(
                            () -> {
                                chain.doFilter(request, response);
                                return null;
                            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // ScopedValue.call declares Exception; nothing else can actually be
            // thrown through a servlet chain.
            throw new ServletException(e);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * A short random id. Not a UUID: these appear in every log line and in Kafka
     * headers on the hot path, and 16 hex characters is enough to disambiguate
     * within a 30-second spike while costing half the bytes.
     */
    private static String generateId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong() | Long.MIN_VALUE);
    }
}
