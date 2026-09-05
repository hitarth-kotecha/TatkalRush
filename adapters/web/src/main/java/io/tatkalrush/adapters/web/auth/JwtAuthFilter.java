package io.tatkalrush.adapters.web.auth;

import io.tatkalrush.adapters.web.ApiError;
import io.tatkalrush.adapters.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FR-59: {@code userId} comes from the token, and from nowhere else.
 *
 * <p>Binds the authenticated caller into {@link RequestContext#USER_ID} for the
 * duration of the request. Controllers read it there; no request DTO has a field
 * it could arrive in instead, which is the actual enforcement — a field plus a
 * validation rule is a field somebody eventually trusts.
 *
 * <h2>What is deliberately not behind this filter</h2>
 *
 * <ul>
 *   <li>{@code /api/v1/auth/token} — where you go to get a token.
 *   <li>{@code /api/v1/payments/webhook} — <b>authenticated by HMAC, not by JWT</b>
 *       (FR-61). The PSP is not a user and holds no token; requiring one would mean
 *       either issuing a credential to an external system or exempting the endpoint
 *       from authentication entirely. Signing the payload is the stronger of the
 *       three: it authenticates the <em>message</em> rather than the connection.
 *   <li>{@code /actuator/**} — Prometheus scrapes these (API-11), and the metrics
 *       endpoint being unauthenticated is a deployment concern (NG-6 puts this
 *       behind a private network), not a bearer-token one.
 *   <li>{@code /psp/**} — the simulator's own surface, which runs in a different
 *       role of the same image.
 * </ul>
 *
 * <p>Listing the exemptions explicitly, rather than matching a protected prefix,
 * means a new endpoint is protected by default. The failure mode of the other
 * arrangement is an endpoint nobody remembered to cover.
 */
@Component
// After CorrelationIdFilter, so a 401 still carries a correlation id in its body.
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private static final List<String> UNPROTECTED =
            List.of("/api/v1/auth/", "/api/v1/payments/webhook", "/actuator", "/psp/");

    private final StubJwt jwt;

    public JwtAuthFilter(StubJwt jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return UNPROTECTED.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            reject(response, "an Authorization: Bearer token is required (FR-59)");
            return;
        }

        var userId = jwt.verify(header.substring(BEARER.length()));
        if (userId.isEmpty()) {
            // Deliberately the same message as a missing token. Which of
            // signature, format or expiry failed is not something an
            // unauthenticated caller can act on, and it is free information for
            // someone probing.
            reject(response, "the token is missing, malformed or expired");
            return;
        }

        try {
            ScopedValue.where(RequestContext.USER_ID, userId.get())
                    .call(
                            () -> {
                                chain.doFilter(request, response);
                                return null;
                            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // ScopedValue.call declares Exception; nothing below it throws a
            // checked type other than the two rethrown above.
            throw new IllegalStateException(e);
        }
    }

    private void reject(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(ApiError.UNAUTHORIZED.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write(
                        """
                        {"type":"%s","title":"UNAUTHORIZED","status":401,\
                        "code":"UNAUTHORIZED","detail":"%s","correlationId":"%s"}"""
                                .formatted(
                                        ApiError.UNAUTHORIZED.typeUri(),
                                        detail,
                                        RequestContext.correlationId()));
    }
}
