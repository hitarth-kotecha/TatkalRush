package io.tatkalrush.adapters.paymentsim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * FR-55 and FR-61: HMAC-SHA256 over the webhook body.
 *
 * <h2>Over the bytes that are actually sent</h2>
 *
 * <p>{@link #sign(byte[])} takes the exact byte array that goes on the wire, and
 * {@link #verify} takes the exact byte array that came off it. Signing a parsed
 * object and re-serialising to verify is the standard way this goes wrong: the two
 * serialisations differ by a space, a key order, or a number format, and every
 * signature fails for a reason that has nothing to do with the key.
 *
 * <p>It is also why FR-61's verification has to happen at the HTTP edge, before
 * the body is parsed. Parsing unauthenticated input is itself the attack surface
 * the signature exists to close.
 *
 * <h2>Constant-time comparison, and why {@code equals} is not enough</h2>
 *
 * <p>{@link MessageDigest#isEqual} compares every byte regardless of where the
 * first difference is. {@code String.equals} and {@code Arrays.equals} return as
 * soon as they find one — so the time they take reveals how many leading bytes
 * were right. An attacker who can send many requests and measure the response can
 * recover a valid signature one byte at a time, without ever knowing the key.
 *
 * <p>FR-61 names this requirement explicitly, which is unusual for a spec and
 * correct: it is the single easiest thing to get wrong here, and the failure is
 * invisible in testing because a timing side channel does not make any test fail.
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /** The header the signature travels in. */
    public static final String SIGNATURE_HEADER = "X-Psp-Signature";

    private final byte[] secret;

    public WebhookSigner(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("the webhook signing secret is required (FR-61)");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** @return the signature as lowercase hex */
    public String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            // A missing HmacSHA256 or a malformed key is a deployment fault, not
            // a request fault. Failing here beats silently sending unsigned
            // webhooks that the receiver would then reject one at a time.
            throw new IllegalStateException("cannot sign webhooks", e);
        }
    }

    /**
     * Whether {@code presented} is the signature for {@code body}.
     *
     * <p>A missing or malformed header is a rejection, not an exception. FR-61
     * requires unsigned and mis-signed webhooks to be "rejected and counted", and
     * both are the same answer to the caller — the difference belongs in a metric,
     * not in the control flow.
     */
    public boolean verify(byte[] body, String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }

        byte[] expected = sign(body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);

        // isEqual, never Arrays.equals or String.equals. See the class comment.
        // It is also safe against differing lengths, which is why no length check
        // precedes it - an early return on length would leak the length.
        return MessageDigest.isEqual(expected, actual);
    }
}
