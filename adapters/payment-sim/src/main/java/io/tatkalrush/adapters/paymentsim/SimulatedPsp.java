package io.tatkalrush.adapters.paymentsim;

import io.tatkalrush.adapters.paymentsim.OutcomeMix.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * The simulated payment service provider (§12, FR-52 to FR-57).
 *
 * <h2>What this is for</h2>
 *
 * <p>Not a stub standing in for a real PSP. An <b>instrument</b>: the failures
 * FR-22, FR-23 and FR-24 are written against are exactly the ones a real payment
 * processor will not produce on request. A webhook that never arrives, a success
 * that lands after the hold expired, the same webhook twice — you cannot ask
 * Razorpay for those, so the only way to test the code that handles them is to own
 * the thing that causes them. FR-56 exists so a chaos scenario can change the mix
 * while a run is in flight.
 *
 * <h2>The outcome is drawn once, at charge time</h2>
 *
 * <p>Both the verdict and the settlement delay are decided when the charge
 * arrives, and then stored. Drawing again at delivery would let FR-23's poll
 * answer {@code SUCCESS} while the webhook says {@code FAILED} — a PSP
 * contradicting itself. That is not a scenario under test; it is a defect in the
 * instrument, and it would send an investigation into the settlement code looking
 * for a bug that is here.
 *
 * <h2>Seeded, because FR-50 requires two runs to be comparable</h2>
 *
 * <p>§9.4's whole argument is a controlled comparison between Strategy A and
 * Strategy B. A simulator that made different decisions each run would put PSP
 * luck into the difference between them, and the comparison would measure that
 * instead. The seed is a constructor argument and belongs in the benchmark
 * report's NFR-12 metadata alongside the JDK build.
 */
public final class SimulatedPsp {

    /** What the PSP knows about one charge. */
    public record SimulatedPayment(
            String reference,
            long bookingId,
            long amountPaise,
            Outcome outcome,
            Instant settlesAt,
            boolean settled,
            boolean refunded) {

        /** FR-23's poll answers from this, whatever the delivery did. */
        public String remoteStatus() {
            if (refunded) {
                return "REFUNDED";
            }
            if (!settled) {
                return "INITIATED";
            }
            return outcome.captured() ? "SUCCESS" : "FAILED";
        }
    }

    private final Map<String, SimulatedPayment> payments = new ConcurrentHashMap<>();
    private final AtomicReference<OutcomeMix> mix;
    private final AtomicReference<LatencyDistribution> latency;
    private final RandomGenerator random;
    private final InstantSource clock;
    private final DelayedExecutor executor;
    private final WebhookDelivery delivery;
    private final Duration lateDelay;

    /**
     * @param lateDelay how far past its normal settlement a {@code LATE_SUCCESS}
     *     lands. Must exceed FR-17's 120-second hold TTL or the outcome stops
     *     being late in the only sense that matters — it would confirm normally
     *     and manufacture none of the FR-24 races C5 is measuring.
     */
    public SimulatedPsp(
            OutcomeMix mix,
            LatencyDistribution latency,
            RandomGenerator random,
            InstantSource clock,
            DelayedExecutor executor,
            WebhookDelivery delivery,
            Duration lateDelay) {
        this.mix = new AtomicReference<>(mix);
        this.latency = new AtomicReference<>(latency);
        this.random = random;
        this.clock = clock;
        this.executor = executor;
        this.delivery = delivery;
        this.lateDelay = lateDelay;
    }

    // ── FR-52: accept a charge ──────────────────────────────────────────────

    /**
     * Accepts a charge and answers immediately (FR-52). Settlement is asynchronous
     * (FR-53).
     *
     * <p>Idempotent on the reference, which is the client's key. A retried charge
     * must return the <em>existing</em> payment rather than draw a second outcome
     * — a PSP that charged twice for one idempotency key would be a defect this
     * project is specifically about not having.
     */
    public SimulatedPayment charge(String reference, long bookingId, long amountPaise) {
        return payments.computeIfAbsent(
                reference,
                ref -> {
                    var currentMix = mix.get();
                    Outcome outcome = currentMix.draw(random);
                    Duration delay = latency.get().sample(random);

                    if (outcome.delivery() == Outcome.Delivery.LATE) {
                        delay = delay.plus(lateDelay);
                    }

                    var payment =
                            new SimulatedPayment(
                                    ref,
                                    bookingId,
                                    amountPaise,
                                    outcome,
                                    clock.instant().plus(delay),
                                    false,
                                    false);

                    executor.schedule(() -> settle(ref), delay);
                    return payment;
                });
    }

    private void settle(String reference) {
        var settled =
                payments.computeIfPresent(
                        reference,
                        (ref, p) ->
                                new SimulatedPayment(
                                        p.reference(),
                                        p.bookingId(),
                                        p.amountPaise(),
                                        p.outcome(),
                                        p.settlesAt(),
                                        true,
                                        p.refunded()));

        if (settled == null || settled.outcome().delivery() == Outcome.Delivery.NEVER) {
            // NO_WEBHOOK settles at the PSP and volunteers nothing. A poll will
            // answer SUCCESS; the caller only ever learns that by asking, which is
            // the entire content of FR-23.
            return;
        }

        deliver(settled);

        // FR-55: the same event, again, for 5% of payments. Not a bug being
        // simulated - at-least-once delivery is what the network actually
        // provides, and a receiver that cannot survive it is broken whether or
        // not this simulator exercises it.
        if (mix.get().drawDuplicate(random)) {
            deliver(settled);
        }
    }

    private void deliver(SimulatedPayment payment) {
        delivery.deliver(
                payment.reference(),
                payment.outcome().captured() ? "PAYMENT_SUCCEEDED" : "PAYMENT_FAILED",
                payment.amountPaise());
    }

    // ── FR-23's poll ────────────────────────────────────────────────────────

    /**
     * What the PSP believes about a payment.
     *
     * <p>Empty means it has never heard of the reference — which is not the same
     * as a failure. It is what the caller sees when it wrote the payment intent
     * and then crashed before the charge call landed, and it is the case that
     * makes "intent first, charge second" recoverable.
     */
    public Optional<SimulatedPayment> poll(String reference) {
        return Optional.ofNullable(payments.get(reference));
    }

    // ── FR-57: refunds ──────────────────────────────────────────────────────

    /** @return empty if there is nothing to refund */
    public Optional<SimulatedPayment> refund(String reference, long amountPaise) {
        return Optional.ofNullable(
                payments.computeIfPresent(
                        reference,
                        (ref, p) -> {
                            if (!p.settled() || !p.outcome().captured()) {
                                // Refusing to refund an unsettled or failed
                                // payment is the behaviour a real PSP has, and the
                                // one that would catch a caller refunding against
                                // an intent rather than a capture.
                                return p;
                            }
                            return new SimulatedPayment(
                                    p.reference(),
                                    p.bookingId(),
                                    p.amountPaise(),
                                    p.outcome(),
                                    p.settlesAt(),
                                    true,
                                    true);
                        }));
    }

    // ── FR-56: reconfigure mid-run ──────────────────────────────────────────

    /**
     * Replaces the outcome mix and latency distribution while the run continues.
     *
     * <p>Payments already accepted keep the verdict they were given. A chaos
     * scenario that retroactively changed decisions already made would produce a
     * PSP whose past answers contradict its present ones, and any confirmation
     * that had already acted on one would look like a defect in the caller.
     */
    public void reconfigure(OutcomeMix newMix, LatencyDistribution newLatency) {
        mix.set(newMix);
        latency.set(newLatency);
    }

    public OutcomeMix currentMix() {
        return mix.get();
    }

    /** Runs a task after a delay. A seam, so tests need not wait out FR-53's tail. */
    public interface DelayedExecutor {
        void schedule(Runnable task, Duration delay);
    }

    /** Sends one webhook. A seam, so tests need no HTTP server. */
    public interface WebhookDelivery {
        void deliver(String paymentReference, String eventType, long amountPaise);
    }
}
