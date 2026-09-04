package io.tatkalrush.adapters.web;

import java.util.NoSuchElementException;

/**
 * Per-request context carried in {@link ScopedValue}s rather than
 * {@code ThreadLocal}s (SDD §8.5, §15.3).
 *
 * <p><b>Why not ThreadLocal.</b> Spring MVC runs on virtual threads here, and the
 * point of that choice is that thousands of concurrently blocked handlers are
 * affordable — in Strategy B an HTTP handler blocks awaiting a reply from a
 * partition owner over Kafka. But every {@code ThreadLocal} entry is per-thread
 * state, so at tens of thousands of threads it stops being free, and a value left
 * uncleared on a pooled carrier thread leaks one request's identity into the
 * next.
 *
 * <p>A {@code ScopedValue} is immutable, bound only for the duration of a call,
 * and unbound automatically when that call returns — the leak is structurally
 * impossible rather than merely avoided by discipline. It is also inherited by
 * {@code StructuredTaskScope} forks, which matters for the search fan-out in
 * §8.5: each concurrent train lookup sees the same correlation id without it
 * being passed as an argument through every layer.
 *
 * <p>Scoped values are <b>final</b> in Java 25 (JEP 506), so this class needs no
 * {@code --enable-preview}, unlike {@code StructuredTaskScope} in the same
 * module.
 */
public final class RequestContext {

    /**
     * Correlation id for the current request. Set at the edge by nginx
     * ({@code X-Correlation-Id}, see ops/nginx/nginx.conf) or generated here if
     * absent, and propagated into Kafka command headers in Phase 2 so a booking
     * can be traced across Strategy B's request/reply hop.
     */
    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

    /**
     * Admission token for the current request (FR-32..FR-37). Bound only in
     * queued mode; unbound is the normal case, not an error.
     */
    public static final ScopedValue<String> ADMISSION_TOKEN = ScopedValue.newInstance();

    private RequestContext() {}

    /** The current correlation id, or {@code "none"} outside a request scope. */
    public static String correlationId() {
        try {
            return CORRELATION_ID.isBound() ? CORRELATION_ID.get() : "none";
        } catch (NoSuchElementException e) {
            return "none";
        }
    }
}
