package io.tatkalrush.domain.inventory;

import java.util.List;

/**
 * The outcome of an allocation attempt (Appendix A).
 *
 * <p>A sealed type rather than a nullable return or an exception. Unavailability
 * is not an error — it is the expected outcome for most requests during a Tatkal
 * spike, and FR-51 excludes {@code SEAT_UNAVAILABLE} from the error budget for
 * exactly that reason. Modelling it as an exception would make the normal case
 * expensive and would invite callers to catch and ignore it.
 */
public sealed interface AllocationResult {

    /** Whether berths were allocated. */
    default boolean isAllocated() {
        return this instanceof Allocated;
    }

    /**
     * Berths were allocated and a hold was created.
     *
     * @param holdId the caller's hold identifier
     * @param berthOrdinals pool ordinals of the allocated berths, ascending —
     *     ascending because FR-5 allocates lowest-ordinal-first, and T-7 asserts
     *     the Lua implementation chose the <em>same</em> berths, not merely an
     *     equally valid set
     * @param range the segment range now occupied on each of those berths
     */
    record Allocated(String holdId, List<Integer> berthOrdinals, SegmentRange range)
            implements AllocationResult {

        public Allocated {
            berthOrdinals = List.copyOf(berthOrdinals);
        }

        public int berthCount() {
            return berthOrdinals.size();
        }
    }

    /**
     * Not enough berths were free for the whole request (FR-6: all or nothing).
     *
     * <p>Carries what was found so the caller can distinguish "one short" from
     * "nothing at all" — the difference between a train that is nearly full and
     * one that is full, which matters to admission control (FR-32) and to the
     * RAC/WL fallback the caller falls through to.
     *
     * @param available berths that were free, always less than {@code requested}
     * @param requested passengers on the booking
     */
    record Unavailable(int available, int requested) implements AllocationResult {}
}
