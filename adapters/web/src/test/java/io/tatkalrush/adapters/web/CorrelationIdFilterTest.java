package io.tatkalrush.adapters.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** SDD §15.3: every request carries a correlation id, propagated via ScopedValue. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("an id supplied by nginx at the edge is honoured, not replaced")
    void honoursIncomingHeader() throws Exception {
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader(CorrelationIdFilter.HEADER, "edge-assigned-id");
        var response = new MockHttpServletResponse();

        var seen = new AtomicReference<String>();
        FilterChain chain = (req, res) -> seen.set(RequestContext.correlationId());

        filter.doFilter(request, response, chain);

        assertEquals("edge-assigned-id", seen.get(), "the edge's id must survive into the app");
        assertEquals("edge-assigned-id", response.getHeader(CorrelationIdFilter.HEADER));
    }

    @Test
    @DisplayName("a missing id is generated, so no request is untraceable")
    void generatesIdWhenAbsent() throws Exception {
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        var response = new MockHttpServletResponse();

        var seen = new AtomicReference<String>();
        filter.doFilter(request, response, (req, res) -> seen.set(RequestContext.correlationId()));

        assertNotNull(seen.get());
        assertNotEquals("none", seen.get(), "an unbound scope means the filter did not run");
        assertEquals(seen.get(), response.getHeader(CorrelationIdFilter.HEADER));
    }

    @Test
    @DisplayName("two requests get distinct ids")
    void idsAreDistinctPerRequest() throws Exception {
        var first = new AtomicReference<String>();
        var second = new AtomicReference<String>();

        filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> first.set(RequestContext.correlationId()));
        filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> second.set(RequestContext.correlationId()));

        assertNotEquals(first.get(), second.get());
    }

    @Test
    @DisplayName("the ScopedValue unbinds and the MDC is cleared once the request ends")
    void contextDoesNotLeakPastTheRequest() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {});

        // ScopedValue unbinding is structural - it cannot leak. MDC is
        // ThreadLocal and CAN, which is precisely why the filter clears it in a
        // finally block. On a virtual-thread carrier a leaked MDC entry would
        // attribute one request's log lines to the next.
        assertFalse(
                RequestContext.CORRELATION_ID.isBound(),
                "ScopedValue still bound after the request returned");
        assertTrue(
                MDC.get("correlationId") == null,
                "MDC still holds a correlation id; the next request would inherit it");
        assertEquals("none", RequestContext.correlationId());
    }

    @Test
    @DisplayName("MDC is cleared even when the handler throws")
    void clearsContextOnFailure() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        try {
            filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                        throw new IllegalStateException("handler blew up");
                    });
        } catch (Exception expected) {
            // The failure path is the one that matters: an exception escaping
            // without clearing the MDC is how a leak survives into production.
        }

        assertTrue(MDC.get("correlationId") == null, "MDC leaked after a failed request");
        assertFalse(RequestContext.CORRELATION_ID.isBound());
    }
}
