package io.tatkalrush.adapters.paymentsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.adapters.paymentsim.OutcomeMix.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §12's simulator, which is an instrument rather than a stub.
 *
 * <p>The tests worth reading are the ones asserting that the two "broken" outcomes
 * still <em>settle</em>: {@code lateSuccessSettlesSuccessfullyJustSlowly} and
 * {@code noWebhookSettlesAndSaysNothing}. Make either of them a failure instead and
 * the simulator looks like it exercises FR-23 and FR-24 while exercising neither.
 */
class SimulatedPspTest {

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final Duration LATE = Duration.ofSeconds(150);

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-53: the latency distribution")
    class Latency {

        @Test
        void theParametersAreSolvedFromTheMedianAndP99() {
            var distribution =
                    new LatencyDistribution(
                            Duration.ofMillis(800), Duration.ofSeconds(6), Duration.ofSeconds(60));

            // mu = ln(0.8); sigma = (ln(6) - ln(0.8)) / 2.3263
            assertEquals(Math.log(0.8), distribution.mu(), 1e-9);
            assertEquals((Math.log(6.0) - Math.log(0.8)) / 2.3263478740408408,
                    distribution.sigma(), 1e-9);
        }

        @Test
        void theSamplesActuallyLandOnThoseQuantiles() {
            var distribution = LatencyDistribution.defaults();
            var random = new Random(42);

            var samples = new ArrayList<Long>();
            for (int i = 0; i < 20_000; i++) {
                samples.add(distribution.sample(random).toMillis());
            }
            samples.sort(Long::compareTo);

            long median = samples.get(10_000);
            long p99 = samples.get(19_800);

            // Wide bands: this is checking that the algebra was solved, not that
            // 20,000 draws reproduce a distribution exactly.
            assertTrue(median > 700 && median < 900, "median was " + median + " ms");
            assertTrue(p99 > 5_000 && p99 < 7_500, "p99 was " + p99 + " ms");
        }

        /**
         * The tail is unbounded, and one draw in roughly a million lands past a
         * minute — long enough to look like a hang rather than a slow payment.
         */
        @Test
        void noSampleExceedsTheCap() {
            var distribution =
                    new LatencyDistribution(
                            Duration.ofMillis(800), Duration.ofSeconds(6), Duration.ofSeconds(5));
            var random = new Random(7);

            for (int i = 0; i < 5_000; i++) {
                assertTrue(distribution.sample(random).compareTo(Duration.ofSeconds(5)) <= 0);
            }
        }

        /**
         * p99 below the median gives a NEGATIVE sigma, which inverts the
         * distribution — the slow tail becomes a fast one, and the simulator looks
         * well-behaved under exactly the configuration meant to stress it.
         */
        @Test
        void aP99BelowTheMedianIsRefused() {
            var thrown =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new LatencyDistribution(
                                            Duration.ofSeconds(6),
                                            Duration.ofMillis(800),
                                            Duration.ofSeconds(60)));

            assertTrue(thrown.getMessage().contains("must exceed"), thrown.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-54: the outcome mix")
    class Mix {

        @Test
        void weightsAreDrawnInProportion() {
            var mix = new OutcomeMix(70, 10, 10, 10, 0);
            var random = new Random(1);
            var counts = new java.util.EnumMap<Outcome, Integer>(Outcome.class);

            for (int i = 0; i < 10_000; i++) {
                counts.merge(mix.draw(random), 1, Integer::sum);
            }

            assertTrue(counts.get(Outcome.SUCCESS) > 6_700, counts.toString());
            assertTrue(counts.get(Outcome.SUCCESS) < 7_300, counts.toString());
            assertTrue(counts.get(Outcome.NO_WEBHOOK) > 800, counts.toString());
            assertTrue(counts.get(Outcome.NO_WEBHOOK) < 1_200, counts.toString());
        }

        @Test
        void aZeroWeightOutcomeIsNeverDrawn() {
            var mix = new OutcomeMix(1, 0, 0, 0, 0);
            var random = new Random(3);

            for (int i = 0; i < 500; i++) {
                assertEquals(Outcome.SUCCESS, mix.draw(random));
            }
        }

        @Test
        void allZeroWeightsAreRefusedRatherThanDividingByZeroLater() {
            assertThrows(IllegalArgumentException.class, () -> new OutcomeMix(0, 0, 0, 0, 5));
        }

        /**
         * The distinction the whole simulator rests on: two of the four outcomes
         * are <em>successes</em> that arrive badly, not failures.
         */
        @Test
        void bothDeliveryFailuresStillCaptureMoney() {
            assertTrue(Outcome.LATE_SUCCESS.captured(), "FR-24 has nothing to refund otherwise");
            assertTrue(Outcome.NO_WEBHOOK.captured(), "FR-23 has nothing to recover otherwise");
            assertFalse(Outcome.FAILURE.captured());
        }

        @Test
        void deliveryAndOutcomeAreIndependent() {
            assertEquals(Outcome.Delivery.PROMPT, Outcome.SUCCESS.delivery());
            assertEquals(Outcome.Delivery.LATE, Outcome.LATE_SUCCESS.delivery());
            assertEquals(Outcome.Delivery.NEVER, Outcome.NO_WEBHOOK.delivery());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-52, FR-55, FR-56: charging and settling")
    class Charging {

        @Test
        void aChargeIsAcceptedImmediatelyAndSettlesLater() {
            var harness = harnessAlways(Outcome.SUCCESS, 0);

            var payment = harness.psp.charge("ref-1", 1L, 145_000L);

            assertFalse(payment.settled(), "FR-52 answers before FR-53 settles");
            assertTrue(harness.delivered.isEmpty());

            harness.runScheduled();

            assertEquals(List.of("ref-1/PAYMENT_SUCCEEDED"), harness.delivered);
            assertEquals("SUCCESS", harness.psp.poll("ref-1").orElseThrow().remoteStatus());
        }

        @Test
        void aRetriedChargeReturnsTheSamePaymentRatherThanDrawingAgain() {
            var harness = harnessAlways(Outcome.SUCCESS, 0);

            var first = harness.psp.charge("ref-1", 1L, 145_000L);
            var second = harness.psp.charge("ref-1", 1L, 145_000L);

            assertEquals(first.settlesAt(), second.settlesAt());
            assertEquals(1, harness.scheduled.size(), "a second charge would settle twice");
        }

        /** T-C5, and FR-55 sets this to 5% by default. */
        @Test
        void aDuplicateDeliveryIsTheSameEventTwice() {
            var harness = harnessAlways(Outcome.SUCCESS, 100);

            harness.psp.charge("ref-1", 1L, 145_000L);
            harness.runScheduled();

            assertEquals(
                    List.of("ref-1/PAYMENT_SUCCEEDED", "ref-1/PAYMENT_SUCCEEDED"),
                    harness.delivered,
                    "at-least-once is what the network provides; FR-55 makes it reproducible");
        }

        /** FR-24's race: money captured, but long after FR-17's 120 s hold TTL. */
        @Test
        void lateSuccessSettlesSuccessfullyJustSlowly() {
            var harness = harnessAlways(Outcome.LATE_SUCCESS, 0);

            harness.psp.charge("ref-1", 1L, 145_000L);

            assertTrue(
                    harness.scheduled.getFirst().delay().compareTo(Duration.ofSeconds(120)) > 0,
                    "a late success arriving inside the hold TTL is just a success: "
                            + harness.scheduled.getFirst().delay());

            harness.runScheduled();

            assertEquals(List.of("ref-1/PAYMENT_SUCCEEDED"), harness.delivered);
            assertEquals(
                    "SUCCESS",
                    harness.psp.poll("ref-1").orElseThrow().remoteStatus(),
                    "the money IS captured — that is what makes FR-24 a refund and not a failure");
        }

        /** FR-23's whole reason for existing. */
        @Test
        void noWebhookSettlesAndSaysNothing() {
            var harness = harnessAlways(Outcome.NO_WEBHOOK, 100);

            harness.psp.charge("ref-1", 1L, 145_000L);
            harness.runScheduled();

            assertTrue(harness.delivered.isEmpty(), "silence is the point");
            assertEquals(
                    "SUCCESS",
                    harness.psp.poll("ref-1").orElseThrow().remoteStatus(),
                    "and the poll answers correctly — the caller only learns by asking");
        }

        @Test
        void aFailureDeliversAFailedEvent() {
            var harness = harnessAlways(Outcome.FAILURE, 0);

            harness.psp.charge("ref-1", 1L, 145_000L);
            harness.runScheduled();

            assertEquals(List.of("ref-1/PAYMENT_FAILED"), harness.delivered);
            assertEquals("FAILED", harness.psp.poll("ref-1").orElseThrow().remoteStatus());
        }

        @Test
        void anUnknownReferenceIsEmptyNotAFailure() {
            var harness = harnessAlways(Outcome.SUCCESS, 0);

            assertTrue(
                    harness.psp.poll("never-seen").isEmpty(),
                    "UNKNOWN and FAILED are different: one is our crash before the charge "
                            + "landed, the other is the PSP declining");
        }

        /**
         * The verdict is drawn once. Drawn again at delivery, a poll could say
         * SUCCESS while the webhook says FAILED — a PSP contradicting itself,
         * which sends an investigation into the settlement code for a bug that is
         * in the instrument.
         */
        @Test
        void thePollAndTheWebhookNeverDisagree() {
            var harness = harnessRandom(new Random(11), 0);

            for (int i = 0; i < 200; i++) {
                harness.psp.charge("ref-" + i, i, 1_000L);
            }
            harness.runScheduled();

            for (int i = 0; i < 200; i++) {
                var payment = harness.psp.poll("ref-" + i).orElseThrow();
                String expected =
                        payment.outcome().captured() ? "PAYMENT_SUCCEEDED" : "PAYMENT_FAILED";

                if (payment.outcome().delivery() != Outcome.Delivery.NEVER) {
                    assertTrue(
                            harness.delivered.contains("ref-" + i + "/" + expected),
                            "poll says "
                                    + payment.remoteStatus()
                                    + " but no matching webhook for ref-"
                                    + i);
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-56: reconfiguring mid-run")
    class Chaos {

        @Test
        void aNewMixAppliesToSubsequentCharges() {
            var harness = harnessAlways(Outcome.SUCCESS, 0);

            harness.psp.charge("before", 1L, 1_000L);
            harness.psp.reconfigure(
                    new OutcomeMix(0, 0, 0, 1, 0), LatencyDistribution.defaults());
            harness.psp.charge("after", 2L, 1_000L);

            assertEquals(Outcome.SUCCESS, harness.psp.poll("before").orElseThrow().outcome());
            assertEquals(Outcome.NO_WEBHOOK, harness.psp.poll("after").orElseThrow().outcome());
        }

        /**
         * A payment already accepted keeps its verdict. Retroactively changing one
         * would make a confirmation that had already acted on it look like a
         * defect in the caller.
         */
        @Test
        void reconfiguringDoesNotRewriteDecisionsAlreadyMade() {
            var harness = harnessAlways(Outcome.SUCCESS, 0);
            harness.psp.charge("ref-1", 1L, 1_000L);

            harness.psp.reconfigure(
                    new OutcomeMix(0, 1, 0, 0, 0), LatencyDistribution.defaults());
            harness.runScheduled();

            assertEquals(List.of("ref-1/PAYMENT_SUCCEEDED"), harness.delivered);
        }

        @Test
        void theC5PresetIsMostlyDeliveryFailuresRatherThanPaymentFailures() {
            var c5 = OutcomeMix.chaosC5();

            assertEquals(0, c5.failureWeight(), "C5 degrades DELIVERY, not the payments");
            assertTrue(
                    c5.lateSuccessWeight() + c5.noWebhookWeight() > c5.successWeight(),
                    "the scenario exists to make FR-23 and FR-24 the common path");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-57: refunds")
    class Refunds {

        @Test
        void aSettledCapturedPaymentCanBeRefunded() {
            var harness = harnessAlways(Outcome.SUCCESS, 0);
            harness.psp.charge("ref-1", 1L, 145_000L);
            harness.runScheduled();

            var refunded = harness.psp.refund("ref-1", 145_000L).orElseThrow();

            assertTrue(refunded.refunded());
            assertEquals("REFUNDED", refunded.remoteStatus());
        }

        @Test
        void anUnsettledPaymentIsNotRefundable() {
            var harness = harnessAlways(Outcome.SUCCESS, 0);
            harness.psp.charge("ref-1", 1L, 145_000L);

            var result = harness.psp.refund("ref-1", 145_000L).orElseThrow();

            assertFalse(
                    result.refunded(),
                    "refunding an intent returns money that was never taken");
        }

        @Test
        void aFailedPaymentIsNotRefundable() {
            var harness = harnessAlways(Outcome.FAILURE, 0);
            harness.psp.charge("ref-1", 1L, 145_000L);
            harness.runScheduled();

            assertFalse(harness.psp.refund("ref-1", 145_000L).orElseThrow().refunded());
        }

        @Test
        void anUnknownReferenceCannotBeRefunded() {
            var harness = harnessAlways(Outcome.SUCCESS, 0);

            assertTrue(harness.psp.refund("never-seen", 100L).isEmpty());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-55/FR-61: the signature")
    class Signing {

        private final WebhookSigner signer = new WebhookSigner("a-test-secret");

        @Test
        void aSignatureVerifiesAgainstTheBytesItCovered() {
            byte[] body = "{\"paymentReference\":\"ref-1\"}".getBytes();

            assertTrue(signer.verify(body, signer.sign(body)));
        }

        @Test
        void oneChangedByteInvalidatesIt() {
            byte[] body = "{\"amountPaise\":145000}".getBytes();
            String signature = signer.sign(body);

            byte[] tampered = "{\"amountPaise\":145001}".getBytes();

            assertFalse(signer.verify(tampered, signature), "one paise must break it");
        }

        @Test
        void aDifferentSecretProducesADifferentSignature() {
            byte[] body = "{}".getBytes();

            assertNotEquals(signer.sign(body), new WebhookSigner("another-secret").sign(body));
        }

        @Test
        void aMissingOrMalformedSignatureIsARejectionNotAnException() {
            byte[] body = "{}".getBytes();

            // FR-61: unsigned and mis-signed are both "rejected and counted", and
            // both are the same answer here. The difference belongs in a metric.
            assertFalse(signer.verify(body, null));
            assertFalse(signer.verify(body, ""));
            assertFalse(signer.verify(body, "not-hex"));
            assertFalse(signer.verify(body, "a".repeat(64)));
        }

        @Test
        void anEmptySecretIsRefusedAtConstruction() {
            assertThrows(IllegalArgumentException.class, () -> new WebhookSigner(""));
            assertThrows(IllegalArgumentException.class, () -> new WebhookSigner(null));
        }
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private record Scheduled(Runnable task, Duration delay) {}

    /** Collects scheduled work instead of waiting for it. */
    private static final class Harness {
        final SimulatedPsp psp;
        final List<Scheduled> scheduled = new ArrayList<>();
        final List<String> delivered = new ArrayList<>();

        Harness(OutcomeMix mix, Random random) {
            this.psp =
                    new SimulatedPsp(
                            mix,
                            LatencyDistribution.defaults(),
                            random,
                            InstantSource.fixed(NOW),
                            (task, delay) -> scheduled.add(new Scheduled(task, delay)),
                            (reference, eventType, amount) ->
                                    delivered.add(reference + "/" + eventType),
                            LATE);
        }

        void runScheduled() {
            var pending = List.copyOf(scheduled);
            scheduled.clear();
            pending.forEach(s -> s.task().run());
        }
    }

    /** A mix that always draws {@code outcome}. */
    private static Harness harnessAlways(Outcome outcome, int duplicatePercent) {
        OutcomeMix mix =
                switch (outcome) {
                    case SUCCESS -> new OutcomeMix(1, 0, 0, 0, duplicatePercent);
                    case FAILURE -> new OutcomeMix(0, 1, 0, 0, duplicatePercent);
                    case LATE_SUCCESS -> new OutcomeMix(0, 0, 1, 0, duplicatePercent);
                    case NO_WEBHOOK -> new OutcomeMix(0, 0, 0, 1, duplicatePercent);
                };
        return new Harness(mix, new Random(5));
    }

    private static Harness harnessRandom(Random random, int duplicatePercent) {
        return new Harness(new OutcomeMix(60, 15, 15, 10, duplicatePercent), random);
    }
}
