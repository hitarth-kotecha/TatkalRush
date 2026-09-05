package io.tatkalrush.adapters.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * RFC 7807 {@code application/problem+json} responses (§11).
 *
 * <p>Beyond the standard {@code type}, {@code title}, {@code status} and
 * {@code detail}, every body carries two extensions:
 *
 * <ul>
 *   <li>{@code code} — §11.2's error code, because three codes share {@code 429}
 *       and three share {@code 409}, and a client or dashboard splitting on status
 *       cannot tell admission pressure from a rate limit.
 *   <li>{@code correlationId} — so a user reporting "it said 409" can be matched to
 *       a request in the structured logs without asking them to reproduce it.
 * </ul>
 */
public final class ApiProblem {

    private ApiProblem() {}

    public static ResponseEntity<Map<String, Object>> of(ApiError error, String detail) {
        return of(error, detail, Map.of());
    }

    /**
     * @param extras additional members, e.g. {@code opensAt} for
     *     {@code QUOTA_LOCKED}. FR-29 requires the opening instant to be in the
     *     response: a client that knows when to come back waits, and one that does
     *     not polls — which is the herd FR-30 exists to avoid.
     */
    public static ResponseEntity<Map<String, Object>> of(
            ApiError error, String detail, Map<String, Object> extras) {

        // LinkedHashMap: RFC 7807's members read best in a fixed order, and a
        // response body that reorders itself between requests is needlessly
        // annoying to diff in a bug report.
        var body = new LinkedHashMap<String, Object>();
        body.put("type", error.typeUri());
        body.put("title", error.name());
        body.put("status", error.status().value());
        body.put("code", error.name());
        body.put("detail", detail);
        body.put("correlationId", RequestContext.correlationId());
        body.putAll(extras);

        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
