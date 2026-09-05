package io.tatkalrush.adapters.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * FR-58's stub JWT issuer, and FR-59's verifier.
 *
 * <p><b>This is a stub and must never be treated otherwise.</b> FR-58 is explicit:
 * "No password, documented as a stub." It hands a signed token to anyone who asks
 * for a user id, which is an authentication bypass in any setting where
 * authentication matters. It exists because FR-59 requires every booking to carry a
 * {@code userId} that the request body cannot supply, and §19 requires 5,000
 * synthetic users to obtain tokens without a login flow nobody is testing.
 *
 * <h2>The verifier never reads the token's {@code alg} header</h2>
 *
 * <p>This is the JWT vulnerability, and it is worth understanding rather than
 * copying. A JWT's header declares which algorithm signed it. A verifier that
 * <em>trusts</em> that declaration can be handed {@code {"alg":"none"}} — and a
 * long list of libraries historically accepted it and verified nothing at all. The
 * sibling attack is algorithm confusion: hand an RS256 verifier a token marked
 * HS256 and signed with the RSA <em>public</em> key as the HMAC secret, and it
 * validates, because the public key is public.
 *
 * <p>The defence is not to check the header more carefully. It is to <b>not read it
 * at all</b>: the verifier decides the algorithm, because the verifier is the only
 * party that knows what it is willing to accept. {@link #verify} hardcodes HS256
 * and never parses {@code alg}.
 *
 * <p>Arguably over-careful for something this disposable. It is the same amount of
 * code, and a stub that models the mistake is a stub someone copies.
 *
 * <h2>A JWT is signed, not secret</h2>
 *
 * <p>The payload is base64url, not encryption — anyone holding the token can read
 * the user id in it. That is fine here and would not be if it carried anything
 * worth hiding, which is one more reason FR-62 keeps personal data out of this
 * system entirely.
 */
public final class StubJwt {

    private static final String ALGORITHM = "HmacSHA256";

    /** Fixed. Deliberately not read back from the token — see the class comment. */
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration lifetime;
    private final InstantSource clock;

    public StubJwt(String secret, Duration lifetime, InstantSource clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("a signing secret is required");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.lifetime = lifetime;
        this.clock = clock;
    }

    /** @return a signed token carrying {@code userId} as its subject */
    public String issue(long userId) {
        Instant now = clock.instant();
        String payload =
                "{\"sub\":\"%d\",\"iat\":%d,\"exp\":%d}"
                        .formatted(userId, now.getEpochSecond(), now.plus(lifetime).getEpochSecond());

        String unsigned = encode(HEADER_JSON) + "." + encode(payload);
        return unsigned + "." + sign(unsigned);
    }

    /**
     * Verifies a token and extracts its subject.
     *
     * <p>Every failure — malformed, mis-signed, expired, unparseable subject —
     * returns empty rather than throwing, and deliberately without saying which.
     * A caller cannot act differently on the difference, and telling an
     * unauthenticated client whether its signature or its expiry was wrong is free
     * information for someone probing.
     */
    public Optional<Long> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        // The header is NOT consulted. parts[0] could say alg:none, alg:RS256 or
        // be gibberish; this verifier signs with HS256 and compares, because the
        // only party entitled to choose the algorithm is the one holding the key.
        String unsigned = parts[0] + "." + parts[1];

        byte[] expected = sign(unsigned).getBytes(StandardCharsets.UTF_8);
        byte[] presented = parts[2].getBytes(StandardCharsets.UTF_8);

        // Constant-time, for the same reason as the webhook signature: a
        // comparison that returns early leaks how many leading bytes were right.
        if (!MessageDigest.isEqual(expected, presented)) {
            return Optional.empty();
        }

        try {
            String payload = new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8);

            long exp = extractNumber(payload, "\"exp\":");
            if (clock.instant().getEpochSecond() >= exp) {
                return Optional.empty();
            }

            return Optional.of(extractNumber(payload, "\"sub\":\""));

        } catch (RuntimeException e) {
            // A well-signed token with an unreadable payload means the issuer and
            // the verifier disagree about the format. Not a client's problem to
            // solve, and not something to guess at.
            return Optional.empty();
        }
    }

    private String sign(String unsigned) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign tokens", e);
        }
    }

    private static String encode(String json) {
        return ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads one numeric claim.
     *
     * <p>Hand-parsed rather than routed through Jackson: the payload of a token
     * whose signature has <em>already</em> been verified is trusted input, and two
     * claims do not justify a dependency. The signature check above is the part
     * that must not be hand-rolled carelessly, and it is not.
     */
    private static long extractNumber(String payload, String key) {
        int start = payload.indexOf(key);
        if (start < 0) {
            throw new IllegalArgumentException("missing claim " + key);
        }
        int from = start + key.length();
        int to = from;
        while (to < payload.length() && Character.isDigit(payload.charAt(to))) {
            to++;
        }
        return Long.parseLong(payload.substring(from, to));
    }
}
