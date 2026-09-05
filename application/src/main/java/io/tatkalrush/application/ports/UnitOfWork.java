package io.tatkalrush.application.ports;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs work inside one transaction.
 *
 * <p>A port rather than a Spring annotation because the transaction boundary is
 * <b>part of the design</b> here, not plumbing. FR-19's guarantee depends on the
 * idempotency claim and the booking insert committing together: a claim that
 * commits early releases the callers blocked on it before a booking exists, and
 * they read a {@code Pending} that never resolves.
 *
 * <p>Making it explicit also means the use case can say, in its own code, that a
 * rollback is what releases the claim — which is how {@code SEAT_UNAVAILABLE}
 * leaves a key reusable rather than burnt.
 */
public interface UnitOfWork {

    /**
     * Runs {@code work} in a transaction, committing on normal return and rolling
     * back on any exception.
     */
    default <T> T inTransaction(Supplier<T> work) {
        return inTransaction(work, result -> false);
    }

    /**
     * Runs {@code work} in a transaction, rolling back — without throwing — when
     * {@code rollbackIf} accepts the result.
     *
     * <p><b>Why this exists.</b> Some outcomes are correct answers that must
     * nonetheless leave no trace. FR-51 is explicit that {@code SEAT_UNAVAILABLE}
     * is "a correct outcome, not an error", so signalling it with an exception
     * would contradict the requirement in order to satisfy the framework. But it
     * must still roll back: the rollback is what releases the idempotency claim,
     * and a committed claim would burn the key so the client could never retry it
     * against a train that has since freed a berth.
     *
     * <p>Without this overload the two demands are irreconcilable — return and the
     * claim persists, throw and a routine outcome becomes an error. This was found
     * by implementing the port: {@code HoldSeats} had a comment asserting that
     * returning {@code SeatUnavailable} rolled back, and the only thing making that
     * true was a test fake that inspected return values on its own initiative.
     * Production would have committed.
     *
     * @param rollbackIf evaluated on the returned value; the value is still
     *     returned to the caller, only the transaction is discarded
     */
    <T> T inTransaction(Supplier<T> work, Predicate<T> rollbackIf);
}
