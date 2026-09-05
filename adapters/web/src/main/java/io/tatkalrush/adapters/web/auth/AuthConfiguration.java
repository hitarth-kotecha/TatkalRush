package io.tatkalrush.adapters.web.auth;

import java.time.Duration;
import java.time.InstantSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires FR-58's stub issuer. */
@Configuration
public class AuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthConfiguration.class);

    @Bean
    StubJwt stubJwt(
            @Value("${tatkalrush.auth.secret:tatkal-rush-dev-secret}") String secret,
            @Value("${tatkalrush.auth.token-lifetime-minutes:120}") long lifetimeMinutes) {

        // Loud, at startup, every time. FR-58 says "documented as a stub", and a
        // line in a javadoc is documentation for whoever reads the source. This is
        // documentation for whoever is looking at a running system and wondering
        // why /auth/token never asked for a password.
        log.warn(
                "AUTH IS A STUB (FR-58): POST /api/v1/auth/token issues a token for any "
                        + "user id, with no credential check. NG-2 puts real identity out of "
                        + "scope; this exists so FR-59 has a userId and §19 can run 5,000 "
                        + "synthetic users.");

        return new StubJwt(secret, Duration.ofMinutes(lifetimeMinutes), InstantSource.system());
    }
}
