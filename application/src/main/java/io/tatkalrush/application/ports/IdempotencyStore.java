package io.tatkalrush.application.ports;

import java.util.Optional;

/**
 * The idempotency mechanism FR-19 specifies, and T-5 tests.
 *
 * <p><b>Insert first, then allocate.</b> A caller claims the key <em>before</em>
 * doing any work. Check-then-act — "does this key exist? no? then allocate" — is
 * explicitly a defect (FR-19), and a deceptive one: two threads both check, both
 * see nothing, and both allocate. The window is narrow, so it passes in
 * development and surfaces under load as an intermittent failure that reads like
 * a load-test artefact rather than a race.
 *
 * <p><b>How the claim is made safe.</b> {@link #claim} inserts under a primary
 * key inside the caller's transaction. When many threads do that concurrently
 * with the same key, Postgres makes all but one <em>block on the unique index</em>
 * until the first transaction resolves — they wait rather than failing. If the
 * winner commits, the others see the conflict and read a {@code bookingId} that
 * is already set, because it was written in the same transaction. If the winner
 * rolls back, one of them succeeds and becomes the new winner. The index lock
 * does the serialising; there is no application lock and no retry loop.
 *
 * <p><b>What is stored is a reference, never a response</b> (DD-010). A frozen
 * 200 replayed at t=300 s would assert a hold that expired at t=120 s — an
 * affirmative lie that manufactures FR-24 races which never happened and
 * contaminates chaos scenario C5's measurement. Replay re-renders from current
 * booking state, so the answer is always true at the moment it is given.
 */
public interface IdempotencyStore {

    /** Outcome of claiming a key. */
    sealed interface Claim {

        /**
         * This caller owns the key and must do the work.
         *
         * <p>The caller is then obliged to either {@link #complete} the claim or
         * roll back its transaction. Doing neither leaves the key claimed with no
         * booking behind it, and every retry of that request blocks on it.
         */
        record Won() implements Claim {}

        /**
         * Another caller already owns this key; this request is a duplicate.
         *
         * @param bookingId the winner's booking. Present because the winner wrote
         *     it in the transaction whose commit released this caller — so by the
         *     time a loser observes the conflict, the answer exists.
         */
        record Duplicate(long bookingId) implements Claim {}

        /**
         * The key exists but carries no booking yet: the winner's transaction is
         * still open, or it failed after claiming.
         *
         * <p>Distinct from {@link Duplicate} because the caller cannot answer the
         * request. FR-19's edge case maps this to the existing {@code RETRY_LATER}
         * path rather than inventing a new error, so the client retries with the
         * <em>same</em> key and converges instead of allocating again.
         */
        record Pending() implements Claim {}

        /**
         * The key was used before with a different request body.
         *
         * <p>A client bug, not a retry — returning the first request's answer for
         * a different question would silently confirm the wrong journey. §11.2
         * maps this to {@code 409 IDEMPOTENCY_KEY_REUSED}.
         */
        record Reused(String existingRequestHash) implements Claim {}
    }

    /**
     * Claims a key, or reports who already holds it.
     *
     * <p>Must run inside the caller's transaction, and that transaction must stay
     * open until the work is done — the blocking behaviour other callers rely on
     * <em>is</em> the lock held by this insert.
     *
     * @param requestHash a stable hash of the request body, so a reused key with
     *     different content is detected rather than answered
     */
    Claim claim(String key, long userId, String requestHash);

    /**
     * Records which booking the claimed key produced.
     *
     * <p>Called in the same transaction as the claim, so that a loser released by
     * the commit sees the booking id rather than a gap.
     */
    void complete(String key, long bookingId);

    /** The booking a key resolved to, if any. Used by replay (FR-19). */
    Optional<Long> bookingIdFor(String key);
}
