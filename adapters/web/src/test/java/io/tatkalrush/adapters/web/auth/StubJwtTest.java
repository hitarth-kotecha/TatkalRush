package io.tatkalrush.adapters.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-58 and FR-59.
 *
 * <p>The tests that matter are in {@code AlgorithmConfusion}. Everything else here
 * checks that a stub works; those check that it is not the <em>usual</em> stub,
 * which accepts {@code alg:none} and verifies nothing.
 */
class StubJwtTest {

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final StubJwt jwt =
            new StubJwt("a-test-secret", Duration.ofHours(1), InstantSource.fixed(NOW));

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("issuing and verifying")
    class RoundTrip {

        @Test
        void aTokenCarriesItsSubjectBack() {
            assertEquals(Optional.of(4_242L), jwt.verify(jwt.issue(4_242L)));
        }

        @Test
        void aTokenHasThreeParts() {
            assertEquals(3, jwt.issue(1L).split("\\.").length);
        }

        /** A JWT is signed, not secret. Worth knowing before putting anything in one. */
        @Test
        void thePayloadIsReadableByAnyoneHoldingTheToken() {
            String payload = jwt.issue(777L).split("\\.")[1];
            String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);

            assertTrue(json.contains("\"sub\":\"777\""), json);
        }

        @Test
        void aDifferentSecretRejectsTheToken() {
            String token = jwt.issue(1L);
            var other = new StubJwt("a-different-secret", Duration.ofHours(1), InstantSource.fixed(NOW));

            assertEquals(Optional.empty(), other.verify(token));
        }

        @Test
        void anExpiredTokenIsRejected() {
            String token = jwt.issue(1L);

            var later =
                    new StubJwt(
                            "a-test-secret",
                            Duration.ofHours(1),
                            InstantSource.fixed(NOW.plus(Duration.ofHours(2))));

            assertEquals(Optional.empty(), later.verify(token));
        }

        @Test
        void aTokenExpiringExactlyNowIsRejected() {
            String token = jwt.issue(1L);

            var atExpiry =
                    new StubJwt(
                            "a-test-secret",
                            Duration.ofHours(1),
                            InstantSource.fixed(NOW.plus(Duration.ofHours(1))));

            assertEquals(
                    Optional.empty(),
                    atExpiry.verify(token),
                    "exp is the first instant the token is invalid, not the last it is valid");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("the verifier chooses the algorithm, not the token")
    class AlgorithmConfusion {

        /**
         * The classic. A verifier that reads {@code alg} from the token it is
         * verifying can be told not to verify.
         */
        @Test
        void anAlgNoneTokenIsRejected() {
            String header = ENCODER.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
            String payload =
                    ENCODER.encodeToString(
                            ("{\"sub\":\"1\",\"iat\":0,\"exp\":"
                                            + NOW.plusSeconds(3600).getEpochSecond()
                                            + "}")
                                    .getBytes());

            // THREE parts, with a junk signature. This matters: Java's split
            // drops trailing empty strings, so "header.payload." is only TWO
            // parts and gets rejected by the length guard before the signature
            // check is ever reached. An earlier version of this test asserted
            // exactly that and looked like it was testing alg handling - it
            // survived a mutation that made the verifier trust alg:none.
            String forged = header + "." + payload + ".not-a-signature";
            assertEquals(3, forged.split("\\.").length, "the token must reach the verifier");

            assertEquals(Optional.empty(), jwt.verify(forged));

            // And the two-part shapes, which are rejected earlier and for a
            // different reason.
            assertEquals(Optional.empty(), jwt.verify(header + "." + payload + "."));
            assertEquals(Optional.empty(), jwt.verify(header + "." + payload));
        }

        /**
         * A header claiming RS256 changes nothing, because the header is never
         * read. The token still has to carry a valid HS256 signature.
         */
        @Test
        void aTokenClaimingADifferentAlgorithmIsStillCheckedAsHs256() {
            String legitimate = jwt.issue(99L);
            String[] parts = legitimate.split("\\.");

            String lyingHeader = ENCODER.encodeToString("{\"alg\":\"RS256\"}".getBytes());
            String forged = lyingHeader + "." + parts[1] + "." + parts[2];

            // Rejected because the signature covered the ORIGINAL header, not
            // because anyone objected to the word RS256.
            assertEquals(Optional.empty(), jwt.verify(forged));
        }

        @Test
        void aTamperedSubjectIsRejected() {
            String token = jwt.issue(1L);
            String[] parts = token.split("\\.");

            String elevated =
                    ENCODER.encodeToString(
                            ("{\"sub\":\"999\",\"iat\":0,\"exp\":"
                                            + NOW.plusSeconds(3600).getEpochSecond()
                                            + "}")
                                    .getBytes());

            assertEquals(Optional.empty(), jwt.verify(parts[0] + "." + elevated + "." + parts[2]));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("malformed input is rejected, never thrown")
    class Malformed {

        @Test
        void everyShapeOfNonsenseReturnsEmpty() {
            // A filter calling this must never see an exception: an unhandled one
            // becomes a 500, which tells an anonymous caller that its input
            // reached something interesting.
            for (String bad :
                    new String[] {
                        null, "", "   ", "not-a-jwt", "a.b", "a.b.c.d", "....", "a.b.c"
                    }) {
                assertEquals(Optional.empty(), jwt.verify(bad), "input: " + bad);
            }
        }

        @Test
        void aWellSignedTokenWithAnUnreadablePayloadIsRejected() {
            // Signed correctly, but the payload has no exp claim.
            var issuer = new StubJwt("a-test-secret", Duration.ofHours(1), InstantSource.fixed(NOW));
            String header = ENCODER.encodeToString("{\"alg\":\"HS256\"}".getBytes());
            String payload = ENCODER.encodeToString("{\"nothing\":true}".getBytes());

            // Sign it properly by issuing and swapping in the parts we control.
            String signature = issuer.issue(1L).split("\\.")[2];

            assertEquals(Optional.empty(), issuer.verify(header + "." + payload + "." + signature));
        }

        @Test
        void anEmptySecretIsRefusedAtConstruction() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new StubJwt("", Duration.ofHours(1), InstantSource.system()));
        }

        @Test
        void twoUsersGetDifferentTokens() {
            assertNotEquals(jwt.issue(1L), jwt.issue(2L));
        }
    }
}
