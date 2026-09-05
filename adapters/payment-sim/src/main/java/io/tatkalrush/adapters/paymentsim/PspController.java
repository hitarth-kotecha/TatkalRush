package io.tatkalrush.adapters.paymentsim;

import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulator's HTTP surface (§12).
 *
 * <table>
 *   <caption>Endpoints</caption>
 *   <tr><td>{@code POST /psp/payments}</td><td>FR-52 — accept a charge</td></tr>
 *   <tr><td>{@code GET  /psp/payments/{ref}}</td><td>FR-23's poll</td></tr>
 *   <tr><td>{@code POST /psp/refunds}</td><td>FR-57</td></tr>
 *   <tr><td>{@code POST /psp/admin/chaos}</td><td>FR-56 — reconfigure mid-run</td></tr>
 * </table>
 *
 * <p><b>The poll endpoint is not in §12's list, and FR-23 cannot work without
 * it.</b> FR-23 says the reconciliation job "polls the PSP", and every other
 * requirement that depends on recovery — the {@code webhook-never-sent} outcome in
 * FR-54, T-C6 in §13.1 — assumes an answer exists. Adding it rather than inventing
 * a way around it; noted here because a reader comparing this file to §12 should
 * find the extra endpoint explained rather than unexplained.
 */
@RestController
@RequestMapping("/psp")
@Profile("psp-sim")
public class PspController {

    private final SimulatedPsp psp;

    public PspController(SimulatedPsp psp) {
        this.psp = psp;
    }

    // ── FR-52 ───────────────────────────────────────────────────────────────

    public record ChargeRequest(String paymentReference, long bookingId, long amountPaise) {}

    /**
     * Accepts a charge and answers immediately with {@code INITIATED} (FR-52).
     *
     * <p>{@code 202 Accepted}, not {@code 201}. The resource is not created in any
     * settled sense — the charge has been taken in and its outcome will arrive
     * later — and a client that reads {@code 201} as "done" is exactly the client
     * FR-53's asynchrony is trying to break.
     */
    @PostMapping("/payments")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody ChargeRequest request) {
        if (request.paymentReference() == null || request.paymentReference().isBlank()) {
            // The reference is the caller's idempotency key. Generating one here
            // would make a retry a second charge.
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "paymentReference is required"));
        }
        if (request.amountPaise() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "amountPaise must be positive"));
        }

        var payment =
                psp.charge(
                        request.paymentReference(), request.bookingId(), request.amountPaise());

        return ResponseEntity.accepted()
                .body(
                        Map.of(
                                "paymentReference", payment.reference(),
                                "status", "INITIATED",
                                "amountPaise", payment.amountPaise()));
    }

    // ── FR-23's poll ────────────────────────────────────────────────────────

    @GetMapping("/payments/{reference}")
    public ResponseEntity<Map<String, Object>> poll(@PathVariable String reference) {
        return psp.poll(reference)
                .<ResponseEntity<Map<String, Object>>>map(
                        payment ->
                                ResponseEntity.ok(
                                        Map.of(
                                                "paymentReference", payment.reference(),
                                                "status", payment.remoteStatus(),
                                                "amountPaise", payment.amountPaise())))
                // 404 means UNKNOWN, and the caller must read it as "no record",
                // never as "failed". They are different: one is our crash before
                // the charge landed, the other is the PSP declining.
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // ── FR-57 ───────────────────────────────────────────────────────────────

    public record RefundRequest(String paymentReference, long amountPaise, String reason) {}

    @PostMapping("/refunds")
    public ResponseEntity<Map<String, Object>> refund(@RequestBody RefundRequest request) {
        return psp.refund(request.paymentReference(), request.amountPaise())
                .<ResponseEntity<Map<String, Object>>>map(
                        payment -> {
                            if (!payment.refunded()) {
                                // Settled-and-captured is the precondition. A
                                // refund against an intent would return money that
                                // was never taken, and a caller doing that has a
                                // bug worth surfacing rather than absorbing.
                                return ResponseEntity.status(HttpStatus.CONFLICT)
                                        .body(
                                                Map.of(
                                                        "error", "payment is not refundable",
                                                        "status", payment.remoteStatus()));
                            }
                            return ResponseEntity.ok(
                                    Map.of(
                                            "paymentReference", payment.reference(),
                                            "status", "REFUNDED"));
                        })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // ── FR-56 ───────────────────────────────────────────────────────────────

    /**
     * @param latencyMedianMs and {@code latencyP99Ms} are optional; omitting both
     *     changes the outcome mix and leaves the timing alone, which is the common
     *     case for a chaos step
     */
    public record ChaosRequest(
            int successWeight,
            int failureWeight,
            int lateSuccessWeight,
            int noWebhookWeight,
            int duplicateWebhookPercent,
            Long latencyMedianMs,
            Long latencyP99Ms) {}

    @PostMapping("/admin/chaos")
    public ResponseEntity<Map<String, Object>> reconfigure(@RequestBody ChaosRequest request) {
        OutcomeMix mix;
        LatencyDistribution latency;
        try {
            mix =
                    new OutcomeMix(
                            request.successWeight(),
                            request.failureWeight(),
                            request.lateSuccessWeight(),
                            request.noWebhookWeight(),
                            request.duplicateWebhookPercent());

            latency =
                    request.latencyMedianMs() == null || request.latencyP99Ms() == null
                            ? LatencyDistribution.defaults()
                            : new LatencyDistribution(
                                    Duration.ofMillis(request.latencyMedianMs()),
                                    Duration.ofMillis(request.latencyP99Ms()),
                                    Duration.ofSeconds(60));

        } catch (IllegalArgumentException e) {
            // A 400 at the moment the bad configuration arrives, rather than a
            // 500 on the next charge. During a chaos run the operator is watching
            // this response and nothing else.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        psp.reconfigure(mix, latency);

        return ResponseEntity.ok(
                Map.of(
                        "successWeight", mix.successWeight(),
                        "failureWeight", mix.failureWeight(),
                        "lateSuccessWeight", mix.lateSuccessWeight(),
                        "noWebhookWeight", mix.noWebhookWeight(),
                        "duplicateWebhookPercent", mix.duplicateWebhookPercent()));
    }
}
