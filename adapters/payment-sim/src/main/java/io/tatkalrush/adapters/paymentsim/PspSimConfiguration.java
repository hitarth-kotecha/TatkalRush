package io.tatkalrush.adapters.paymentsim;

import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Wires the simulator, under the {@code psp-sim} profile only.
 *
 * <p>§8.3 runs one image in three roles: {@code app-1}, {@code app-2} and
 * {@code psp-sim}. The profile is what separates them, so none of this loads into
 * the application replicas — a simulator reachable from inside the system under
 * test would be a way for a benchmark to accidentally measure itself.
 */
@Configuration
@Profile("psp-sim")
public class PspSimConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PspSimConfiguration.class);

    @Bean
    WebhookSigner webhookSigner(
            @Value("${tatkalrush.psp.secret:tatkal-rush-dev-secret}") String secret) {
        return new WebhookSigner(secret);
    }

    @Bean
    LatencyDistribution latencyDistribution(
            @Value("${tatkalrush.psp.latency-median-ms:800}") long medianMs,
            @Value("${tatkalrush.psp.latency-p99-ms:6000}") long p99Ms) {
        return new LatencyDistribution(
                Duration.ofMillis(medianMs), Duration.ofMillis(p99Ms), Duration.ofSeconds(60));
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService pspScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("psp-scheduler").daemon().factory());
    }

    @Bean
    SimulatedPsp simulatedPsp(
            LatencyDistribution latency,
            ScheduledExecutorService pspScheduler,
            SimulatedPsp.WebhookDelivery delivery,
            @Value("${tatkalrush.psp.seed:20261001}") long seed,
            @Value("${tatkalrush.psp.late-delay-seconds:150}") long lateDelaySeconds) {

        // FR-50 wants two benchmark runs comparable, and the seed is what stops
        // PSP luck landing inside "Strategy B was 12% slower".
        //
        // Be precise about what it buys, though: charges arrive concurrently and
        // consume draws in whatever order the threads interleave, so the SAME
        // payment does not get the same verdict across runs. What is reproducible
        // is the DISTRIBUTION - roughly the same proportion of late successes and
        // silent settlements, drawn from the same stream. That is what the
        // comparison needs; per-payment determinism would need a per-reference
        // derived seed, which nothing so far requires.
        log.info("psp-sim seeded with {} — record this in the NFR-12 metadata block", seed);

        return new SimulatedPsp(
                OutcomeMix.defaults(),
                latency,
                new Random(seed),
                InstantSource.system(),
                (task, delay) ->
                        pspScheduler.schedule(
                                // Fire onto a virtual thread rather than running
                                // on the scheduler's single thread. A slow webhook
                                // POST would otherwise delay every settlement
                                // queued behind it - and under C5, where half the
                                // deliveries are meant to be slow, that turns a
                                // latency scenario into a throughput collapse of
                                // the instrument rather than of the system.
                                () -> Thread.ofVirtual().name("psp-settle").start(task),
                                delay.toMillis(),
                                TimeUnit.MILLISECONDS),
                delivery,
                Duration.ofSeconds(lateDelaySeconds));
    }

    /**
     * Note the import: Spring Boot 4 ships <b>Jackson 3</b>, whose databind
     * package moved from {@code com.fasterxml.jackson.databind} to
     * {@code tools.jackson.databind}. The annotations kept the old coordinates, so
     * a file can compile against {@code com.fasterxml} annotations and fail on a
     * {@code com.fasterxml} ObjectMapper — which reads as a missing dependency
     * rather than a renamed one.
     */
    @Bean
    SimulatedPsp.WebhookDelivery webhookDelivery(
            WebhookSigner signer,
            ObjectMapper objectMapper,
            @Value("${tatkalrush.psp.webhook-url:http://nginx/payments/webhook}") String url) {

        RestClient client = RestClient.builder().build();

        return (reference, eventType, amountPaise) -> {
            try {
                // Serialise ONCE, sign THOSE bytes, send THOSE bytes. Signing a
                // parsed object and re-serialising on the other side is how
                // signatures start failing for reasons unrelated to the key.
                // LinkedHashMap so the field order is fixed, which matters only
                // for reading the logs - the signature covers whatever bytes come
                // out, in whatever order.
                var payload = new LinkedHashMap<String, Object>();
                payload.put("paymentReference", reference);
                payload.put("eventType", eventType);
                payload.put("amountPaise", amountPaise);

                byte[] body = objectMapper.writeValueAsBytes(payload);

                client.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSigner.SIGNATURE_HEADER, signer.sign(body))
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();

            } catch (Exception e) {
                // A webhook that cannot be delivered is not an error here — it is
                // one of the conditions being simulated. INFO rather than WARN so
                // a C5 run is not drowned in stack traces for the behaviour it was
                // configured to produce. FR-23's sweep is what recovers it, and
                // whether that works is the thing under test.
                log.info("webhook delivery failed for {}: {}", reference, e.toString());
            }
        };
    }
}
