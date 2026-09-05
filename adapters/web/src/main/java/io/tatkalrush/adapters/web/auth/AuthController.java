package io.tatkalrush.adapters.web.auth;

import io.tatkalrush.adapters.web.ApiError;
import io.tatkalrush.adapters.web.ApiProblem;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-58: the stub token issuer.
 *
 * <p><b>There is no password.</b> Ask for a user id, receive a token for it. That
 * is an authentication bypass wherever authentication matters, and it is what FR-58
 * specifies — the requirement says "documented as a stub", and NG-2 puts real
 * identity out of scope entirely.
 *
 * <p>It exists for a concrete reason rather than as a placeholder: FR-59 requires
 * every booking to carry a {@code userId} that the request body is not allowed to
 * supply, and §19's profiles need up to 5,000 synthetic users holding tokens
 * (FR-69). A real login flow would be 5,000 password checks measuring nothing this
 * project is about.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final StubJwt jwt;

    public AuthController(StubJwt jwt) {
        this.jwt = jwt;
    }

    /**
     * @param userId a synthetic user's id, from the seeded {@code users} table
     */
    public record TokenRequest(Long userId) {}

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody TokenRequest request) {
        if (request.userId() == null || request.userId() <= 0) {
            return ApiProblem.of(ApiError.INVALID_REQUEST, "userId must be a positive integer");
        }

        // No existence check against the users table. A token for a user id that
        // does not exist will fail at the first foreign key, which is a clearer
        // error than a 404 here and one fewer database round trip on a path that
        // 5,000 virtual users hit at the start of every run.
        return ResponseEntity.ok(
                Map.of(
                        "token", jwt.issue(request.userId()),
                        "tokenType", "Bearer",
                        "warning",
                                "STUB ISSUER (FR-58). No credential was checked. "
                                        + "Not an authentication mechanism."));
    }
}
