package io.tatkalrush.domain.booking;

/**
 * One traveller on a booking.
 *
 * <p>Synthetic by construction. FR-62 admits "no PII beyond synthetic passenger
 * names" and forbids card data entirely — this record is the whole of the personal
 * data this system holds, and it is here so that fact is easy to check rather than
 * inferred from the absence of other fields.
 *
 * <p><b>Why the domain has this at all.</b> A berth is assigned to a person, not to
 * a booking: {@code passengers.berth_id} is the only column in the schema where a
 * held berth can live. Modelling the passengers is therefore not bookkeeping, it is
 * what makes the allocation storable.
 */
public record Passenger(String name, int age, Gender gender) {

    /** Mirrors {@code passengers.gender}'s CHECK constraint. */
    public enum Gender {
        M,
        F,
        O
    }

    public Passenger {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("passenger name is required");
        }
        if (age < 0 || age > 120) {
            // Matches the column's CHECK. Rejecting here rather than at the
            // database means the caller learns which passenger was wrong, instead
            // of the whole insert failing with a constraint name.
            throw new IllegalArgumentException("age must be 0..120, got " + age);
        }
        if (gender == null) {
            throw new IllegalArgumentException("gender is required");
        }
    }
}
