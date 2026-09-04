package io.tatkalrush.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The segment mask algebra (§5.2, FR-1 through FR-4). */
class SegmentMaskTest {

    @Nested
    @DisplayName("Appendix B worked example")
    class AppendixB {

        // Route: NDLS(0) -> KOTA(1) -> RTM(2) -> ST(3) -> BCT(4), 4 segments.
        //
        // This is the table in the SDD's Appendix B and at the top of the README.
        // It is executable here so the documentation cannot drift from the code:
        // if this fails, the README is lying.

        @Test
        @DisplayName("one berth, three passengers, no overbooking")
        void oneBerthServesThreeJourneys() {
            long berth = SegmentMask.EMPTY;
            assertEquals("0000", SegmentMask.render(berth, 4), "empty berth");

            // B1 NDLS->RTM = [0,2) = 0011
            long b1 = SegmentRange.of(0, 2).mask();
            assertTrue(SegmentMask.isFree(berth, b1));
            berth = SegmentMask.allocate(berth, b1);
            assertEquals("1100", SegmentMask.render(berth, 4), "after B1 (segment 0 leftmost)");

            // B2 ST->BCT = [3,4) = 1000. No overlap with B1.
            long b2 = SegmentRange.of(3, 4).mask();
            assertTrue(SegmentMask.isFree(berth, b2));
            berth = SegmentMask.allocate(berth, b2);
            assertEquals("1101", SegmentMask.render(berth, 4), "after B2");

            // B3 KOTA->ST = [1,3) = 0110. Overlaps B1 on segment 1 -> REJECTED.
            long b3 = SegmentRange.of(1, 3).mask();
            assertFalse(
                    SegmentMask.isFree(berth, b3),
                    "B3 shares segment 1 with B1 and must be refused");

            // B4 RTM->ST = [2,3) = 0100. Free: B1 ended at 2, B2 starts at 3.
            long b4 = SegmentRange.of(2, 3).mask();
            assertTrue(
                    SegmentMask.isFree(berth, b4),
                    "B4 sits in the gap between B1 and B2 and must be accepted");
            berth = SegmentMask.allocate(berth, b4);
            assertEquals("1111", SegmentMask.render(berth, 4), "berth now fully sold");

            // B1 cancelled: mask AND NOT 0011.
            berth = SegmentMask.release(berth, b1);
            assertEquals("0011", SegmentMask.render(berth, 4), "after B1 cancelled");
        }
    }

    @Nested
    @DisplayName("half-open semantics")
    class HalfOpen {

        @Test
        @DisplayName("T-3: adjacent journeys share a stop, not a segment")
        void adjacentRangesDoNotOverlap() {
            // The single most important positive case in the system. Delhi->Ratlam
            // and Ratlam->Mumbai meet at Ratlam and must BOTH fit on one berth.
            // If this ever fails, half-open has become inclusive and the system
            // now refuses bookings real railways accept.
            long left = SegmentRange.of(0, 2).mask();
            long right = SegmentRange.of(2, 4).mask();

            assertEquals(0L, left & right, "adjacent ranges must not intersect");
            assertFalse(SegmentRange.of(0, 2).overlaps(SegmentRange.of(2, 4)));

            long berth = SegmentMask.allocate(SegmentMask.EMPTY, left);
            assertTrue(SegmentMask.isFree(berth, right));
        }

        @Test
        @DisplayName("T-2: ranges sharing an interior segment do overlap")
        void interleavedRangesOverlap() {
            // A->C and B->D on a route A-B-C-D: [0,2) and [1,3) share segment 1.
            assertTrue(SegmentRange.of(0, 2).overlaps(SegmentRange.of(1, 3)));

            long berth = SegmentMask.allocate(SegmentMask.EMPTY, SegmentRange.of(0, 2).mask());
            assertFalse(SegmentMask.isFree(berth, SegmentRange.of(1, 3).mask()));
        }

        @ParameterizedTest
        @CsvSource({
            "0, 2, 2, 4, false", // adjacent, meeting at a stop
            "0, 2, 1, 3, true", // share segment 1
            "0, 4, 1, 2, true", // one contains the other
            "1, 2, 1, 2, true", // identical
            "0, 1, 3, 4, false", // disjoint with a gap
            "0, 1, 1, 2, false", // adjacent, single segments
        })
        @DisplayName("overlap agrees with mask intersection in every case")
        void overlapMatchesMaskIntersection(int aF, int aT, int bF, int bT, boolean expected) {
            var a = SegmentRange.of(aF, aT);
            var b = SegmentRange.of(bF, bT);

            assertEquals(expected, a.overlaps(b), a + " vs " + b);
            // The two must never disagree: `overlaps` is the readable form and the
            // mask is the hot-path form, and a divergence would mean the fast path
            // and the comprehensible path answer differently.
            assertEquals(expected, (a.mask() & b.mask()) != 0L, a + " vs " + b + " by mask");
        }
    }

    @Nested
    @DisplayName("the 64-segment boundary (FR-3, FR-3a)")
    class Boundary {

        @Test
        @DisplayName("a full-route booking is all-ones, not zero")
        void fullRouteMaskIsNotEmpty() {
            // THE trap. Written literally, FR-4's ((1L << to) - 1) gives 0 at
            // to == 64, because Java takes shift counts mod 64. A full-route
            // booking would then conflict with nothing, appear available on every
            // berth, and allocate a booking occupying no segments -- with no
            // invariant able to notice, since no berth was in fact double-sold.
            long full = SegmentRange.of(0, 64).mask();

            assertEquals(-1L, full, "[0,64) must be all ones");
            assertEquals(64, SegmentMask.occupiedCount(full));
            assertFalse(SegmentMask.isEmpty(full));
        }

        @Test
        @DisplayName("segment 63 is representable and occupies the sign bit")
        void segment63IsRepresentable() {
            long last = SegmentRange.of(63, 64).mask();

            assertEquals(Long.MIN_VALUE, last, "segment 63 is the sign bit");
            assertEquals(1, SegmentMask.occupiedCount(last));
            assertTrue(last < 0, "the sign bit being set makes the mask negative");
        }

        @Test
        @DisplayName("a booking ending at 63 does not touch segment 63")
        void rangeEndingAtSixtyThreeExcludesIt() {
            long upToButNot63 = SegmentRange.of(0, 63).mask();
            long just63 = SegmentRange.of(63, 64).mask();

            assertEquals(0L, upToButNot63 & just63, "half-open: [0,63) excludes segment 63");
            assertEquals(63, SegmentMask.occupiedCount(upToButNot63));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 31, 32, 33, 62, 63, 64})
        @DisplayName("[0,n) always has exactly n bits set")
        void prefixMasksHaveExpectedWidth(int n) {
            // 32 and 33 are included deliberately: Strategy A splits the mask
            // across two 32-bit halves in Lua (FR-3a, DD-002), so the halfway
            // boundary is where that implementation will diverge if it is wrong.
            assertEquals(n, SegmentMask.occupiedCount(SegmentRange.of(0, n).mask()));
        }
    }

    @Nested
    @DisplayName("range validation")
    class Validation {

        @ParameterizedTest
        @CsvSource({"2, 2", "3, 1", "0, 0", "64, 64"})
        @DisplayName("empty and inverted ranges are refused at construction")
        void rejectsEmptyOrInvertedRanges(int from, int to) {
            // An empty range produces an all-zero mask, which intersects nothing
            // and therefore reports "available" against every berth in the pool.
            // Refusing it here means it can never reach an allocator.
            assertThrows(IllegalArgumentException.class, () -> SegmentRange.of(from, to));
        }

        @ParameterizedTest
        @CsvSource({"-1, 4", "0, 65", "0, 100"})
        @DisplayName("out-of-bounds ranges are refused (FR-3)")
        void rejectsOutOfBounds(int from, int to) {
            assertThrows(IllegalArgumentException.class, () -> SegmentRange.of(from, to));
        }
    }

    @Nested
    @DisplayName("allocate and release")
    class AllocateRelease {

        @Test
        @DisplayName("release is idempotent")
        void releaseIsIdempotent() {
            // Release arrives from hold expiry, cancellation and chart
            // preparation, and the lazy reaper can race the background reaper
            // (§9.2). Idempotence is cheaper than proving every caller fires once.
            long request = SegmentRange.of(1, 3).mask();
            long berth = SegmentMask.allocate(SegmentMask.EMPTY, request);

            long once = SegmentMask.release(berth, request);
            long twice = SegmentMask.release(once, request);

            assertEquals(SegmentMask.EMPTY, once);
            assertEquals(once, twice);
        }

        @Test
        @DisplayName("allocate is idempotent for the same range")
        void allocateIsIdempotent() {
            long request = SegmentRange.of(1, 3).mask();
            long once = SegmentMask.allocate(SegmentMask.EMPTY, request);
            assertEquals(once, SegmentMask.allocate(once, request));
        }

        @Test
        @DisplayName("releasing one booking leaves the others untouched")
        void releaseIsScopedToItsOwnRange() {
            long a = SegmentRange.of(0, 2).mask();
            long b = SegmentRange.of(2, 4).mask();

            long berth = SegmentMask.allocate(SegmentMask.allocate(SegmentMask.EMPTY, a), b);
            long afterCancellingA = SegmentMask.release(berth, a);

            assertEquals(b, afterCancellingA, "cancelling A must not free B's segments");
            assertFalse(SegmentMask.isFree(afterCancellingA, b));
            assertTrue(SegmentMask.isFree(afterCancellingA, a));
        }
    }
}
