package io.tatkalrush.adapters.paymentsim;

import java.util.random.RandomGenerator;

/**
 * FR-54's outcome mix, and FR-55's deliberate double delivery.
 *
 * <p>The four outcomes are not four kinds of failure. They are two independent
 * facts — <em>what the PSP decided</em> and <em>whether we hear about it</em> —
 * and the last two entries exist precisely because those two can come apart:
 *
 * <ul>
 *   <li>{@link Outcome#SUCCESS} / {@link Outcome#FAILURE} — decided, delivered
 *       promptly. The ordinary cases.
 *   <li>{@link Outcome#LATE_SUCCESS} — decided SUCCESS, delivered long after
 *       FR-17's 120-second hold TTL. This is what manufactures FR-24's race, and
 *       it is the whole content of chaos scenario C5.
 *   <li>{@link Outcome#NO_WEBHOOK} — decided SUCCESS, never delivered. The PSP
 *       will answer a poll correctly and volunteer nothing, which is the only
 *       thing that exercises FR-23's reconciliation sweep at all.
 * </ul>
 *
 * <p>Both of the last two settle successfully at the PSP. A simulator that made
 * them <em>failures</em> would look like it was testing the same paths and would
 * test none of them: nothing to refund under FR-24, nothing to recover under
 * FR-23.
 */
public record OutcomeMix(
        int successWeight,
        int failureWeight,
        int lateSuccessWeight,
        int noWebhookWeight,
        int duplicateWebhookPercent) {

    public enum Outcome {
        SUCCESS(true, Delivery.PROMPT),
        FAILURE(false, Delivery.PROMPT),
        LATE_SUCCESS(true, Delivery.LATE),
        NO_WEBHOOK(true, Delivery.NEVER);

        /** How the outcome reaches us, which is independent of what it is. */
        public enum Delivery {
            PROMPT,
            LATE,
            NEVER
        }

        private final boolean captured;
        private final Delivery delivery;

        Outcome(boolean captured, Delivery delivery) {
            this.captured = captured;
            this.delivery = delivery;
        }

        /** Whether money moved. Answers FR-23's poll regardless of delivery. */
        public boolean captured() {
            return captured;
        }

        public Delivery delivery() {
            return delivery;
        }
    }

    public OutcomeMix {
        if (successWeight < 0
                || failureWeight < 0
                || lateSuccessWeight < 0
                || noWebhookWeight < 0) {
            throw new IllegalArgumentException("weights must be non-negative");
        }
        if (successWeight + failureWeight + lateSuccessWeight + noWebhookWeight == 0) {
            // Every weight zero means no outcome can be drawn. Rejecting it here
            // rather than dividing by zero later means a bad FR-56 request is a
            // 400 at the moment it arrives, not a 500 on the next charge.
            throw new IllegalArgumentException("at least one outcome weight must be positive");
        }
        if (duplicateWebhookPercent < 0 || duplicateWebhookPercent > 100) {
            throw new IllegalArgumentException(
                    "duplicateWebhookPercent must be 0..100, got " + duplicateWebhookPercent);
        }
    }

    /** The default mix: mostly fine, with FR-55's 5% double delivery. */
    public static OutcomeMix defaults() {
        return new OutcomeMix(90, 5, 3, 2, 5);
    }

    /** C5: "PSP → 50% timeouts, 20% late-success" (§16's chaos table). */
    public static OutcomeMix chaosC5() {
        return new OutcomeMix(30, 0, 20, 50, 5);
    }

    public int total() {
        return successWeight + failureWeight + lateSuccessWeight + noWebhookWeight;
    }

    /**
     * Draws one outcome.
     *
     * <p>Called <b>once per charge</b>, at charge time, and the verdict is then
     * stored. Drawing again at delivery would let a poll say {@code SUCCESS} while
     * the webhook says {@code FAILED} — a PSP contradicting itself, which is a bug
     * in the instrument rather than a scenario worth reproducing.
     */
    public Outcome draw(RandomGenerator random) {
        int roll = random.nextInt(total());

        if (roll < successWeight) {
            return Outcome.SUCCESS;
        }
        roll -= successWeight;

        if (roll < failureWeight) {
            return Outcome.FAILURE;
        }
        roll -= failureWeight;

        if (roll < lateSuccessWeight) {
            return Outcome.LATE_SUCCESS;
        }
        return Outcome.NO_WEBHOOK;
    }

    /** FR-55: the same webhook sent twice, on purpose. */
    public boolean drawDuplicate(RandomGenerator random) {
        return random.nextInt(100) < duplicateWebhookPercent;
    }
}
