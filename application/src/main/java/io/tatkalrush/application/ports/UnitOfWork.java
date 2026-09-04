package io.tatkalrush.application.ports;

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
    <T> T inTransaction(Supplier<T> work);
}
