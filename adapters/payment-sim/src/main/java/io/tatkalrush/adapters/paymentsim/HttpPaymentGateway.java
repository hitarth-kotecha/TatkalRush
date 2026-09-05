package io.tatkalrush.adapters.paymentsim;

import io.tatkalrush.application.ports.PaymentGateway;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link PaymentGateway} over HTTP, against §12's simulator.
 *
 * <h2>Everything turns on distinguishing "no" from "no answer"</h2>
 *
 * <p>An HTTP client has three outcomes and the middle one is the dangerous one:
 *
 * <ul>
 *   <li>A response saying no — the PSP decided. Nothing was captured.
 *   <li>A timeout, a connection reset, a 5xx — <b>we do not know.</b> The request
 *       may have been processed and the response lost.
 *   <li>A response saying yes.
 * </ul>
 *
 * <p>Every branch below exists to keep the second from being reported as the
 * first. {@code InitiatePayment} treats {@code Rejected} as "mark it failed" and
 * {@code Unreachable} as "leave it alone and let FR-23 find out", and collapsing
 * them here would orphan real money while every log line looked orderly.
 *
 * <p><b>A 5xx is {@code Unreachable}, not {@code Rejected}.</b> A server error is
 * the PSP failing to answer, not answering no — it may have taken the money and
 * fallen over while replying.
 *
 * <h2>Timeouts are short, because the caller is not waiting</h2>
 *
 * <p>FR-53 gives settlement a p99 of six seconds, but FR-52 answers
 * <em>immediately</em>: this client is waiting for an acknowledgement, not an
 * outcome. A long timeout here would hold a request thread through a PSP outage
 * for no benefit, since the answer it is waiting for was never going to contain
 * the settlement.
 */
public final class HttpPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

    private final RestClient client;

    public HttpPaymentGateway(String baseUrl, Duration timeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());

        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public ChargeOutcome charge(ChargeRequest request) {
        try {
            client.post()
                    .uri("/psp/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                            Map.of(
                                    "paymentReference", request.paymentReference(),
                                    "bookingId", request.bookingId(),
                                    "amountPaise", request.amountPaise()))
                    .retrieve()
                    .toBodilessEntity();

            return new ChargeOutcome.Accepted();

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // The PSP answered, and the answer was no. A 4xx means it
                // understood the request and declined it; nothing was captured.
                return new ChargeOutcome.Rejected(
                        "%d: %s".formatted(e.getStatusCode().value(), e.getStatusText()));
            }
            // A 5xx is NOT a rejection. The PSP may have captured the money and
            // then failed while replying, and marking this failed would orphan it.
            return new ChargeOutcome.Unreachable("server error " + e.getStatusCode().value());

        } catch (RuntimeException e) {
            log.info("charge unreachable for {}: {}", request.paymentReference(), e.toString());
            return new ChargeOutcome.Unreachable(e.toString());
        }
    }

    @Override
    public RefundOutcome refund(RefundRequest request) {
        try {
            client.post()
                    .uri("/psp/refunds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                            Map.of(
                                    "paymentReference", request.paymentReference(),
                                    "amountPaise", request.amountPaise(),
                                    "reason", request.reason()))
                    .retrieve()
                    .toBodilessEntity();

            return new RefundOutcome.Accepted();

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                return new RefundOutcome.Rejected(
                        "%d: %s".formatted(e.getStatusCode().value(), e.getStatusText()));
            }
            return new RefundOutcome.Unreachable("server error " + e.getStatusCode().value());

        } catch (RuntimeException e) {
            return new RefundOutcome.Unreachable(e.toString());
        }
    }

    @Override
    public RemoteStatus poll(String paymentReference) {
        try {
            var body =
                    client.get()
                            .uri("/psp/payments/{ref}", paymentReference)
                            .retrieve()
                            .body(Map.class);

            Object status = body == null ? null : body.get("status");
            return switch (String.valueOf(status)) {
                case "SUCCESS", "REFUNDED" -> RemoteStatus.SUCCESS;
                case "FAILED" -> RemoteStatus.FAILED;
                case "INITIATED" -> RemoteStatus.INITIATED;
                default -> RemoteStatus.UNKNOWN;
            };

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                // The PSP has never heard of this reference: we wrote the intent
                // and then failed before the charge landed. UNKNOWN, and FR-23
                // reads it as "no money moved" - which is only safe because it is
                // distinguished from FAILED here.
                return RemoteStatus.UNKNOWN;
            }
            // Any other error means the poll itself failed. INITIATED, so the
            // sweep leaves the payment alone and tries again next cycle - the one
            // answer that changes nothing.
            return RemoteStatus.INITIATED;

        } catch (RuntimeException e) {
            log.info("poll unreachable for {}: {}", paymentReference, e.toString());
            return RemoteStatus.INITIATED;
        }
    }
}
