package io.tatkalrush.domain.inventory;

/**
 * The half-open range of route segments a journey occupies: {@code [fromSeq, toSeq)}.
 *
 * <p><b>Segments, not stations.</b> A route with N stops has N-1 segments, indexed
 * from 0. A journey from stop {@code i} to stop {@code j} occupies segments
 * {@code i} through {@code j-1}:
 *
 * <pre>
 *   Route:    NDLS --0-- KOTA --1-- RTM --2-- ST --3-- BCT
 *   Segments:       0          1         2        3
 *
 *   NDLS -> RTM  is stops 0..2  =  segments {0,1}  =  [0,2)
 *   RTM  -> BCT  is stops 2..4  =  segments {2,3}  =  [2,4)
 * </pre>
 *
 * <p><b>Half-openness is load-bearing, not stylistic.</b> Those two journeys meet
 * at RTM but share no segment, so one berth can serve both (§2.1, test T-3). With
 * inclusive ranges they would collide, and the system would refuse a booking that
 * real railways accept — turning this project's central capability into a bug.
 *
 * @param fromSeq first segment occupied, inclusive
 * @param toSeq one past the last segment occupied, exclusive
 */
public record SegmentRange(int fromSeq, int toSeq) {

    /**
     * A mask is a Java {@code long}, so a route may have at most 64 segments —
     * 65 stops (FR-3). This comfortably exceeds any real Indian Railways route.
     * Exceeding it is a seed-data error, caught at generation time, not a runtime
     * concern.
     */
    public static final int MAX_SEGMENTS = 64;

    public SegmentRange {
        if (fromSeq < 0) {
            throw new IllegalArgumentException("fromSeq must be >= 0, got " + fromSeq);
        }
        if (toSeq > MAX_SEGMENTS) {
            throw new IllegalArgumentException(
                    "toSeq must be <= " + MAX_SEGMENTS + " (FR-3), got " + toSeq);
        }
        // Rejected here rather than tolerated downstream. An empty or inverted
        // range produces an all-zero mask, which conflicts with nothing and
        // therefore appears to succeed against every berth in the pool - an
        // allocation of no segments that reports success.
        if (fromSeq >= toSeq) {
            throw new IllegalArgumentException(
                    "range must be non-empty: fromSeq (%d) < toSeq (%d)"
                            .formatted(fromSeq, toSeq));
        }
    }

    /** Convenience factory reading as the SDD writes ranges. */
    public static SegmentRange of(int fromSeq, int toSeq) {
        return new SegmentRange(fromSeq, toSeq);
    }

    /** The bitmask for this range (FR-4). */
    public long mask() {
        return SegmentMask.of(fromSeq, toSeq);
    }

    /** Number of segments occupied. */
    public int length() {
        return toSeq - fromSeq;
    }

    /**
     * Whether two journeys share at least one segment, and therefore cannot share
     * a berth.
     *
     * <p>Note that {@code [0,2)} and {@code [2,4)} do <b>not</b> overlap: they
     * meet at a stop, not on a leg.
     */
    public boolean overlaps(SegmentRange other) {
        return fromSeq < other.toSeq && other.fromSeq < toSeq;
    }

    @Override
    public String toString() {
        return "[" + fromSeq + "," + toSeq + ")";
    }
}
