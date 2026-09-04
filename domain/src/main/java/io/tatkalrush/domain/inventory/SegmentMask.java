package io.tatkalrush.domain.inventory;

/**
 * The segment bitmask: the central data structure of this system (§5.2).
 *
 * <p>A berth's occupancy across one schedule is a single {@code long}. Bit
 * {@code i} is set if the berth is occupied on segment {@code i}. Availability is
 * then one machine instruction:
 *
 * <pre>
 *   Booking A: NDLS -> RTM  = [0,2) = 0b0011
 *   Booking B: ST   -> BCT  = [3,4) = 0b1000
 *   Berth mask after both   =         0b1011
 *
 *   Request  KOTA -> ST     = [1,3) = 0b0110
 *     0b1011 &amp; 0b0110 = 0b0010  != 0  ->  CONFLICT
 *   Request  RTM  -> ST     = [2,3) = 0b0100
 *     0b1011 &amp; 0b0100 = 0             ->  AVAILABLE
 * </pre>
 *
 * <p><b>Why a bitmask and not a list of intervals.</b> The obvious alternative —
 * keeping each berth's booked intervals and comparing pairwise — is O(bookings)
 * per availability check and allocates on the hot path of every attempt. During a
 * Tatkal spike that path runs thousands of times a second against the same few
 * berths. At a bounded 64 segments the bitmask is not an optimisation of that
 * design; it is a different complexity class, O(1) with no allocation.
 *
 * <p>This class is pure: no state, no time, no I/O. It is the part of the
 * algorithm that Strategy A must reproduce exactly in Lua, and T-7 compares the
 * two step for step.
 */
public final class SegmentMask {

    /** No segments occupied. */
    public static final long EMPTY = 0L;

    private SegmentMask() {}

    /**
     * The mask for {@code [fromSeq, toSeq)} (FR-4).
     *
     * <p><b>The {@code toSeq == 64} trap.</b> FR-4 gives this as
     * {@code ((1L << to) - 1) ^ ((1L << from) - 1)}. Written literally that is
     * wrong at the top of the range: Java takes shift counts <b>mod 64</b>, so
     * {@code 1L << 64} evaluates to {@code 1L << 0} == 1, and
     * {@code (1L << 64) - 1} is <b>0</b> rather than all-ones.
     *
     * <p>A full-route booking would therefore produce an empty mask — conflicting
     * with nothing, appearing available on every berth, and allocating a booking
     * that occupies no segments at all. Nothing downstream would report it: the
     * exclusion constraint would see an empty range, and every invariant would
     * agree that no berth was double-sold.
     *
     * <p>{@code -1L} is all-ones, which is what {@code (1L << 64) - 1} means
     * mathematically. FR-3a requires segment 63 be exercised explicitly by a
     * property test for exactly this reason.
     */
    public static long of(int fromSeq, int toSeq) {
        long high = (toSeq == 64) ? -1L : (1L << toSeq) - 1L;
        long low = (fromSeq == 64) ? -1L : (1L << fromSeq) - 1L;
        return high ^ low;
    }

    /** FR-1: a berth is available for a request iff the masks do not intersect. */
    public static boolean isFree(long berthMask, long requestMask) {
        return (berthMask & requestMask) == 0L;
    }

    /** FR-2: allocation sets the requested bits. */
    public static long allocate(long berthMask, long requestMask) {
        return berthMask | requestMask;
    }

    /**
     * FR-2: release clears the requested bits.
     *
     * <p>Deliberately idempotent — releasing a range twice leaves the same mask.
     * Release arrives from hold expiry, cancellation and chart preparation, and at
     * least one of those can fire twice for the same booking (a lazy reaper racing
     * the background reaper, §9.2). Making the operation idempotent is cheaper and
     * more robust than making every caller prove it only fires once.
     */
    public static long release(long berthMask, long requestMask) {
        return berthMask & ~requestMask;
    }

    /** Segments occupied, for free-count maintenance (INV-12) and diagnostics. */
    public static int occupiedCount(long mask) {
        return Long.bitCount(mask);
    }

    /** Whether the berth is entirely unoccupied. */
    public static boolean isEmpty(long mask) {
        return mask == EMPTY;
    }

    /**
     * Renders a mask as bits, segment 0 on the LEFT.
     *
     * <p>Reversed relative to {@code Long.toBinaryString}, which prints the most
     * significant bit first. Reading left to right as the train travels makes
     * these legible in a test failure; matching the SDD's own tables matters more
     * here than matching Java's default.
     */
    public static String render(long mask, int segments) {
        var sb = new StringBuilder(segments);
        for (int i = 0; i < segments; i++) {
            sb.append((mask & (1L << i)) != 0 ? '1' : '0');
        }
        return sb.toString();
    }
}
