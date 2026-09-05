package io.tatkalrush.adapters.web;

import io.tatkalrush.application.usecases.SettlePayment;
import io.tatkalrush.application.usecases.SettlePayment.WebhookEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.InstantSource;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code API-6}: the PSP callback (FR-22, FR-61).
 *
 * <h2>The handler takes {@code byte[]}, and that is the whole point</h2>
 *
 * <p>FR-61 requires the HMAC to be verified before the body is trusted, and a
 * signature can only be checked against the exact bytes it covered. By the time
 * Spring has populated an {@code @RequestBody SomeDto}, the request body has been
 * consumed and parsed — the bytes are gone, and the parsing already happened, on
 * input nobody had authenticated yet. That parsing is itself the attack surface
 * the signature exists to close.
 *
 * <p>So this one handler declines the framework's convenience: receive bytes,
 * verify, <em>then</em> parse. The ordering is visible in the method rather than
 * implied by configuration somewhere else.
 *
 * <p>A {@code ContentCachingRequestWrapper} filter would also work — buffer the
 * body so both the signature check and the DTO binding can have it. It was
 * rejected for exactly the reason it is attractive: the controller then looks like
 * every other controller, and the security property lives in a filter three files
 * away that a later cleanup removes without anything failing.
 *
 * <h2>Not behind the JWT filter</h2>
 *
 * <p>The PSP is not a user and holds no token. The signature authenticates the
 * <b>message</b> rather than the connection, which is stronger than issuing an
 * external system a bearer credential and far stronger than exempting the endpoint
 * from authentication altogether.
 *
 * <h2>Almost everything answers 200</h2>
 *
 * <p>A PSP that receives an error retries, and for most of what can go wrong here a
 * retry cannot help: a duplicate is already handled, an unknown reference will
 * still be unknown, an out-of-order event is still out of order. Answering 200 and
 * counting the case is how you avoid a retry storm from a system you do not
 * control, arriving during the spike that produced the condition.
 *
 * <p>A bad signature is the exception. That gets a 401 and a counter, because
 * FR-61 requires mis-signed webhooks to be "rejected and counted" and because it
 * is the one case where the sender genuinely should stop and look.
 */
@RestController
// §8.3 runs one image in three roles; this one belongs to app-1 and app-2.
@Profile("!psp-sim")
@RequestMapping("/api/v1/payments")
public class PaymentWebhookController {

    /** Must match {@code WebhookSigner.SIGNATURE_HEADER} in the simulator. */
    private static final String SIGNATURE_HEADER = "X-Psp-Signature";

    private final SettlePayment settlePayment;
    private final WebhookVerifier verifier;
    private final ObjectMapper objectMapper;
    private final InstantSource clock;
    private final Counter rejected;
    private final Counter unparseable;

    public PaymentWebhookController(
            SettlePayment settlePayment,
            WebhookVerifier verifier,
            ObjectMapper objectMapper,
            InstantSource clock,
            MeterRegistry meters) {
        this.settlePayment = settlePayment;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.clock = clock;

        // FR-61: "rejected AND COUNTED". A rejection nobody can see is a rejection
        // that looks like the PSP going quiet, which is a very different problem
        // with a very different response.
        this.rejected =
                Counter.builder("psp_webhook_rejected_total")
                        .description("Webhooks rejected for a missing or invalid signature (FR-61)")
                        .register(meters);
        this.unparseable =
                Counter.builder("psp_webhook_unparseable_total")
                        .description("Webhooks that were correctly signed but could not be read")
                        .register(meters);
    }

    /** Verifies a signature over raw bytes. A seam, so the check can be tested alone. */
    public interface WebhookVerifier {
        boolean verify(byte[] body, String presentedSignature);
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> receive(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] body) {

        // FIRST. Before parsing, before logging the body, before anything reads it.
        if (!verifier.verify(body, signature)) {
            rejected.increment();
            return ApiProblem.of(
                    ApiError.UNAUTHORIZED, "the webhook signature is missing or invalid");
        }

        WebhookEvent event;
        try {
            JsonNode json = objectMapper.readTree(body);
            String reference = json.path("paymentReference").asString();
            String eventType = json.path("eventType").asString();

            if (reference.isBlank()) {
                unparseable.increment();
                return ResponseEntity.ok(Map.of("status", "IGNORED", "reason", "no reference"));
            }

            // Mapped onto OUR enum, which is also what lands in
            // payment_events.event_type. A provider renaming
            // payment.succeeded to payment_succeeded would otherwise open a fresh
            // dedup bucket and the same event would settle twice (DD-034).
            event =
                    new WebhookEvent(
                            reference, toEventType(eventType), new String(body));

        } catch (RuntimeException e) {
            unparseable.increment();
            // Signed correctly but unreadable means the PSP changed its format.
            // Retrying will send the same thing, so 200 and a counter - the
            // counter is what makes someone look.
            return ResponseEntity.ok(
                    Map.of("status", "IGNORED", "reason", "unrecognised payload"));
        }

        var result = settlePayment.handle(event, clock.instant());

        return switch (result) {
            case SettlePayment.Result.Settled settled ->
                    ResponseEntity.ok(
                            Map.of(
                                    "status", "SETTLED",
                                    "bookingId", settled.bookingId(),
                                    "confirmation", settled.confirmation().getClass().getSimpleName()));

            case SettlePayment.Result.Failed failed ->
                    ResponseEntity.ok(Map.of("status", "FAILED", "bookingId", failed.bookingId()));

            // T-C5. FR-55 double-delivers 5% of webhooks on purpose, so this is a
            // designed-for path and a 200 is the correct answer to it.
            case SettlePayment.Result.DuplicateEvent ignored ->
                    ResponseEntity.ok(Map.of("status", "DUPLICATE"));

            case SettlePayment.Result.AlreadySettled already ->
                    ResponseEntity.ok(
                            Map.of("status", "ALREADY_SETTLED", "current", already.current().name()));

            case SettlePayment.Result.UnknownPayment unknown ->
                    // 200, not 404. A 404 makes the PSP retry, and retrying will
                    // not conjure a payment we have no record of. Counted, because
                    // a nonzero rate means references are being lost.
                    ResponseEntity.ok(
                            Map.of("status", "UNKNOWN", "paymentReference", unknown.reference()));
        };
    }

    private static SettlePayment.EventType toEventType(String pspEventType) {
        return switch (pspEventType) {
            case "PAYMENT_SUCCEEDED", "payment.succeeded" -> SettlePayment.EventType.PAYMENT_SUCCEEDED;
            case "PAYMENT_FAILED", "payment.failed" -> SettlePayment.EventType.PAYMENT_FAILED;
            // Rejected here rather than admitted as a new value. An unrecognised
            // type stored verbatim would create a dedup bucket of its own, and the
            // same event could then settle twice.
            default ->
                    throw new IllegalArgumentException("unrecognised event type: " + pspEventType);
        };
    }
}
