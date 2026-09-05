package io.tatkalrush.application.ports;

/**
 * Reports events that mean the software is broken, not that the world is messy.
 *
 * <p>A port so the application layer can raise NFR-9's alarm without depending on
 * Micrometer — but also so the alarm is a named collaborator rather than a log
 * line. §14's whole argument is that correctness claims must be checkable by
 * something other than the code making the claim; a metric that a test can assert
 * on is that something, and a {@code log.error} is not.
 */
public interface IntegrityAlarm {

    /**
     * The {@code no_overlapping_allocations} constraint rejected an insert while
     * the hold was still live (FR-25, INV-11).
     *
     * <p>Implementations increment {@code allocation_constraint_violations_total}.
     * Any non-zero value fails the run under NFR-9 — this is not a counter to
     * watch trend upward, it is a boolean that happens to be stored as a number.
     *
     * @param bookingId the booking whose confirmation was refused
     * @param berthId the berth the constraint refused to allocate twice
     */
    void allocationConstraintViolated(long bookingId, long berthId);
}
