package io.tatkalrush.adapters.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tatkalrush.application.ports.PaymentRepository.PaymentStatus;
import io.tatkalrush.application.usecases.ConfirmBooking;
import io.tatkalrush.application.usecases.SettlePayment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code API-6}, and FR-61's ordering.
 *
 * <p>The test this class exists for is
 * {@code anUnsignedWebhookIsRejectedBeforeAnythingParsesIt}. Everything else is
 * routine HTTP mapping; that one asserts the property that made the handler take
 * {@code byte[]} instead of a DTO.
 */
class PaymentWebhookControllerTest {

    private static final String SECRET = "a-test-secret";
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");

    private SettlePayment settlePayment;
    private SimpleMeterRegistry meters;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        settlePayment = mock(SettlePayment.class);
        meters = new SimpleMeterRegistry();

        var controller =
                new PaymentWebhookController(
                        settlePayment,
                        PaymentWebhookControllerTest::verifySignature,
                        new ObjectMapper(),
                        InstantSource.fixed(NOW),
                        meters);

        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-61: verify first, parse second")
    class Signature {

        /**
         * The body is not JSON at all. If anything parsed it before checking the
         * signature, the response would be a 400 from the parser rather than a
         * 401 from the verifier — and the parse would have happened on
         * unauthenticated input, which is the surface the signature exists to
         * close.
         */
        @Test
        void anUnsignedWebhookIsRejectedBeforeAnythingParsesIt() throws Exception {
            byte[] garbage = "}{ this is not json at all".getBytes(StandardCharsets.UTF_8);

            mvc.perform(
                            post("/api/v1/payments/webhook")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(garbage))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            verify(settlePayment, never()).handle(any(), any());
        }

        @Test
        void aWrongSignatureIsRejected() throws Exception {
            byte[] body = body("ref-1", "PAYMENT_SUCCEEDED");

            mvc.perform(
                            post("/api/v1/payments/webhook")
                                    .header("X-Psp-Signature", "0".repeat(64))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isUnauthorized());

            verify(settlePayment, never()).handle(any(), any());
        }

        /** FR-61: "rejected AND counted". */
        @Test
        void everyRejectionIsCounted() throws Exception {
            byte[] body = body("ref-1", "PAYMENT_SUCCEEDED");

            mvc.perform(post("/api/v1/payments/webhook").content(body));
            mvc.perform(
                    post("/api/v1/payments/webhook")
                            .header("X-Psp-Signature", "deadbeef")
                            .content(body));

            assertEquals(
                    2.0,
                    meters.get("psp_webhook_rejected_total").counter().count(),
                    "a rejection nobody can see looks like the PSP going quiet");
        }

        /**
         * The signature covers the bytes. One changed paise breaks it, which is
         * what stops an attacker replaying a real webhook with a different amount.
         */
        @Test
        void aTamperedBodyIsRejectedEvenWithARealSignature() throws Exception {
            byte[] original = body("ref-1", "PAYMENT_SUCCEEDED");
            String signature = sign(original);

            byte[] tampered = body("ref-2", "PAYMENT_SUCCEEDED");

            mvc.perform(
                            post("/api/v1/payments/webhook")
                                    .header("X-Psp-Signature", signature)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(tampered))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("a signed webhook is handled")
    class Handling {

        @Test
        void aSuccessSettlesAndConfirms() throws Exception {
            when(settlePayment.handle(any(), any()))
                    .thenReturn(
                            new SettlePayment.Result.Settled(
                                    88L,
                                    new ConfirmBooking.Outcome.Confirmed(
                                            88L, "0000000018", List.of(7L))));

            signedPost(body("ref-1", "PAYMENT_SUCCEEDED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SETTLED"))
                    .andExpect(jsonPath("$.bookingId").value(88));
        }

        /** T-C5. FR-55 double-delivers 5% of webhooks on purpose. */
        @Test
        void aDuplicateIsA200NotAnError() throws Exception {
            when(settlePayment.handle(any(), any()))
                    .thenReturn(new SettlePayment.Result.DuplicateEvent());

            signedPost(body("ref-1", "PAYMENT_SUCCEEDED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DUPLICATE"));
        }

        /**
         * A 404 would make the PSP retry, and retrying will not conjure a payment
         * we have no record of.
         */
        @Test
        void anUnknownReferenceIsA200() throws Exception {
            when(settlePayment.handle(any(), any()))
                    .thenReturn(new SettlePayment.Result.UnknownPayment("ref-1"));

            signedPost(body("ref-1", "PAYMENT_SUCCEEDED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNKNOWN"));
        }

        @Test
        void anOutOfOrderEventReportsWhatWasAlreadySettled() throws Exception {
            when(settlePayment.handle(any(), any()))
                    .thenReturn(
                            new SettlePayment.Result.AlreadySettled("ref-1", PaymentStatus.SUCCESS));

            signedPost(body("ref-1", "PAYMENT_FAILED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.current").value("SUCCESS"));
        }

        /**
         * An unrecognised type must not be stored verbatim: {@code event_type} is
         * the dedup key, so a new value opens a fresh bucket and the same event
         * could settle twice (DD-034).
         */
        @Test
        void anUnrecognisedEventTypeIsIgnoredRatherThanStored() throws Exception {
            signedPost(body("ref-1", "payment.partially_refunded_maybe"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IGNORED"));

            verify(settlePayment, never()).handle(any(), any());
            assertTrue(meters.get("psp_webhook_unparseable_total").counter().count() > 0);
        }

        @Test
        void bothTheProvidersSpellingAndOursAreAccepted() throws Exception {
            when(settlePayment.handle(any(), any()))
                    .thenReturn(new SettlePayment.Result.DuplicateEvent());

            signedPost(body("ref-1", "payment.succeeded")).andExpect(status().isOk());
            signedPost(body("ref-1", "PAYMENT_SUCCEEDED")).andExpect(status().isOk());
        }

        @Test
        void aSignedButUnreadableBodyIsIgnoredNotRetried() throws Exception {
            signedPost("{\"nothing\":\"useful\"}".getBytes(StandardCharsets.UTF_8))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IGNORED"));

            verify(settlePayment, never()).handle(any(), any());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions signedPost(byte[] body)
            throws Exception {
        return mvc.perform(
                post("/api/v1/payments/webhook")
                        .header("X-Psp-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static byte[] body(String reference, String eventType) {
        return ("{\"paymentReference\":\"%s\",\"eventType\":\"%s\",\"amountPaise\":145000}")
                .formatted(reference, eventType)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The same HMAC the simulator computes, built independently here. */
    private static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean verifySignature(byte[] body, String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(body).getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
