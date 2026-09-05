package io.tatkalrush.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The reference allocator (Appendix A, FR-5, FR-6, §9.2). */
class BerthPoolTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final long TTL = 120_000; // FR-16's 120 s hold window

    private static AllocationResult.Allocated allocated(AllocationResult result) {
        return assertInstanceOf(
                AllocationResult.Allocated.class,
                result,
                () -> "expected an allocation, got " + result);
    }

    @Nested
    @DisplayName("FR-5: deterministic berth choice")
    class BerthChoice {

        @Test
        @DisplayName("allocates the lowest ordinal that fits")
        void picksLowestOrdinal() {
            var pool = new BerthPool(10, 4);

            var first = allocated(pool.allocate(SegmentRange.of(0, 2), 1, "h1", T0, TTL));
            assertEquals(List.of(0), first.berthOrdinals());

            // Berth 0 is now busy on [0,2), so an overlapping request moves on.
            var second = allocated(pool.allocate(SegmentRange.of(1, 3), 1, "h2", T0, TTL));
            assertEquals(List.of(1), second.berthOrdinals());

            // But a NON-overlapping request comes back to berth 0. This is the
            // whole point of segment-wise inventory: the lowest berth that fits,
            // not the lowest berth that is empty.
            var third = allocated(pool.allocate(SegmentRange.of(2, 4), 1, "h3", T0, TTL));
            assertEquals(List.of(0), third.berthOrdinals());
        }

        @Test
        @DisplayName("berth choice is repeatable, which is what makes T-7 assertable")
        void choiceIsRepeatable() {
            // T-7 asserts the Lua implementation picks the SAME berths, not merely
            // an equally valid set. That only means something if this side is
            // deterministic.
            var a = new BerthPool(8, 4);
            var b = new BerthPool(8, 4);

            for (int i = 0; i < 5; i++) {
                var ra = allocated(a.allocate(SegmentRange.of(0, 3), 1, "h" + i, T0, TTL));
                var rb = allocated(b.allocate(SegmentRange.of(0, 3), 1, "h" + i, T0, TTL));
                assertEquals(ra.berthOrdinals(), rb.berthOrdinals());
            }
        }

        @Test
        @DisplayName("group bookings need not be adjacent (FR-7)")
        void groupBerthsNeedNotBeAdjacent() {
            var pool = new BerthPool(5, 4);
            // Occupy berths 1 and 3 on an overlapping range.
            allocated(pool.allocate(SegmentRange.of(0, 4), 1, "x", T0, TTL));
            allocated(pool.allocate(SegmentRange.of(0, 4), 1, "y", T0, TTL));

            var group = allocated(pool.allocate(SegmentRange.of(0, 4), 3, "g", T0, TTL));
            assertEquals(List.of(2, 3, 4), group.berthOrdinals());
        }
    }

    @Nested
    @DisplayName("FR-6: all or nothing")
    class Atomicity {

        @Test
        @DisplayName("a group that does not fit allocates nothing at all")
        void partialAllocationNeverHappens() {
            var pool = new BerthPool(3, 4);
            var range = SegmentRange.of(0, 4);

            var result = pool.allocate(range, 5, "group", T0, TTL);

            var unavailable =
                    assertInstanceOf(AllocationResult.Unavailable.class, result);
            assertEquals(3, unavailable.available());
            assertEquals(5, unavailable.requested());

            // The critical part: nothing was taken. A partial allocation would
            // leave three berths held for a booking that failed - the orphaned
            // hold §1 claims this system does not produce.
            assertEquals(0, pool.liveHoldCount());
            assertEquals(3, pool.freeOn(range), "no berth may have been consumed");
            for (int i = 0; i < 3; i++) {
                assertEquals(SegmentMask.EMPTY, pool.maskAt(i), "berth " + i + " was touched");
            }
            pool.checkInvariants();
        }

        @Test
        @DisplayName("exactly-enough berths succeed")
        void exactFitSucceeds() {
            var pool = new BerthPool(3, 4);
            var result = allocated(pool.allocate(SegmentRange.of(0, 4), 3, "g", T0, TTL));

            assertEquals(3, result.berthCount());
            assertEquals(0, pool.freeOn(SegmentRange.of(0, 4)));
            pool.checkInvariants();
        }
    }

    @Nested
    @DisplayName("free counts (INV-12)")
    class FreeCounts {

        @Test
        @DisplayName("counts start at the full berth count on every segment")
        void countsStartFull() {
            var pool = new BerthPool(72, 24);
            assertEquals(72, pool.remainingBerths());
            pool.checkInvariants();
        }

        @Test
        @DisplayName("only the occupied segments are decremented")
        void decrementIsScopedToTheRange() {
            var pool = new BerthPool(10, 6);
            allocated(pool.allocate(SegmentRange.of(2, 4), 2, "h", T0, TTL));

            assertEquals(10, pool.freeOn(SegmentRange.of(0, 2)), "untouched segments");
            assertEquals(8, pool.freeOn(SegmentRange.of(2, 4)), "occupied segments");
            assertEquals(10, pool.freeOn(SegmentRange.of(4, 6)), "untouched segments");

            // remainingBerths is the minimum across ALL segments - deliberately
            // conservative, so a train with one busy segment reads as busy.
            assertEquals(8, pool.remainingBerths());
            pool.checkInvariants();
        }

        @Test
        @DisplayName("release restores the counts exactly")
        void releaseRestoresCounts() {
            var pool = new BerthPool(10, 6);
            allocated(pool.allocate(SegmentRange.of(1, 5), 3, "h", T0, TTL));
            assertEquals(7, pool.remainingBerths());

            assertTrue(pool.release("h"));

            assertEquals(10, pool.remainingBerths(), "capacity must return exactly to baseline");
            pool.checkInvariants();
        }

        @Test
        @DisplayName("the invariant check actually catches drift")
        void invariantCheckHasTeeth() throws Exception {
            // A check that cannot fail is decoration, and INV-12 is the guard on
            // a counter that seven code paths mutate. Real drift is unreachable
            // through the public API by design, so it is injected here: without
            // this, checkInvariants() would pass on every test in this file while
            // being incapable of ever failing.
            var pool = new BerthPool(4, 3);
            allocated(pool.allocate(SegmentRange.of(0, 2), 2, "h", T0, TTL));
            pool.checkInvariants();

            var field = BerthPool.class.getDeclaredField("freeCount");
            field.setAccessible(true);
            int[] counts = (int[]) field.get(pool);
            int original = counts[1];
            counts[1] = original + 1; // the counter now claims a berth that is held

            var drift =
                    assertThrows(
                            IllegalStateException.class,
                            pool::checkInvariants,
                            "checkInvariants passed on a pool whose free count was wrong");
            assertTrue(
                    drift.getMessage().contains("segment 1"),
                    () -> "the failure must name the drifting segment, got: " + drift.getMessage());

            counts[1] = original;
            pool.checkInvariants();
        }
    }

    @Nested
    @DisplayName("FR-43: releasing a CONFIRMED booking")
    class ReleasingConfirmed {

        /** confirm() drops the hold record, so release() can no longer reach it. */
        @Test
        @DisplayName("release cannot reach a confirmed booking, which is why this exists")
        void releaseCannotReachAConfirmedBooking() {
            var pool = new BerthPool(4, 4);
            pool.allocate(SegmentRange.of(0, 4), 1, "h1", T0, TTL);
            pool.confirm("h1");

            assertFalse(pool.release("h1"), "the hold record is gone by design");
            assertEquals(3, pool.freeOn(SegmentRange.of(0, 4)), "and the berth is still taken");
        }

        @Test
        @DisplayName("it frees the berths and restores the counts")
        void freesTheBerths() {
            var pool = new BerthPool(4, 4);
            var allocated = allocatedBy(pool, SegmentRange.of(0, 4), 2, "h1", T0, TTL);
            pool.confirm("h1");

            int cleared = pool.releaseConfirmed(SegmentRange.of(0, 4), allocated.berthOrdinals());

            assertEquals(8, cleared, "two berths across four segments");
            assertEquals(4, pool.freeOn(SegmentRange.of(0, 4)));
            pool.checkInvariants();
        }

        /**
         * The test this whole operation is careful for.
         *
         * <p>A retried cancellation must free the berths once. Counting the berths
         * it was HANDED rather than the bits it actually CLEARED would add them a
         * second time, and the pool would report more berths than it has - INV-12's
         * drift, on the counter upstream of the metric §9.4 rests on.
         */
        @Test
        @DisplayName("running it twice frees the berths once")
        void isIdempotent() {
            var pool = new BerthPool(4, 4);
            var allocated = allocatedBy(pool, SegmentRange.of(0, 4), 2, "h1", T0, TTL);
            pool.confirm("h1");

            pool.releaseConfirmed(SegmentRange.of(0, 4), allocated.berthOrdinals());
            int second = pool.releaseConfirmed(SegmentRange.of(0, 4), allocated.berthOrdinals());

            assertEquals(0, second, "the second run cleared nothing and must have counted nothing");
            assertEquals(4, pool.freeOn(SegmentRange.of(0, 4)), "not 6");
            pool.checkInvariants();
        }

        /** Segment-wise inventory: another booking on a different leg is untouched. */
        @Test
        @DisplayName("it frees only the cancelled booking's segments")
        void leavesOtherLegsAlone() {
            var pool = new BerthPool(1, 4);

            var first = allocatedBy(pool, SegmentRange.of(0, 2), 1, "h1", T0, TTL);
            pool.confirm("h1");
            var second = allocatedBy(pool, SegmentRange.of(2, 4), 1, "h2", T0, TTL);
            pool.confirm("h2");

            assertEquals(first.berthOrdinals(), second.berthOrdinals(), "T-3: one berth, two legs");

            pool.releaseConfirmed(SegmentRange.of(0, 2), first.berthOrdinals());

            assertEquals(1, pool.freeOn(SegmentRange.of(0, 2)), "the cancelled leg is free");
            assertEquals(0, pool.freeOn(SegmentRange.of(2, 4)), "the other passenger still has it");
            pool.checkInvariants();
        }

        @Test
        @DisplayName("releasing berths that were never taken is a no-op, not a gift")
        void releasingFreeBerthsChangesNothing() {
            var pool = new BerthPool(4, 4);

            int cleared = pool.releaseConfirmed(SegmentRange.of(0, 4), java.util.List.of(0, 1));

            assertEquals(0, cleared);
            assertEquals(4, pool.freeOn(SegmentRange.of(0, 4)), "still four, not six");
            pool.checkInvariants();
        }

        @Test
        @DisplayName("a partially-overlapping range frees only the bits that were set")
        void countsOnlyTheBitsThatWereSet() {
            var pool = new BerthPool(1, 4);
            var allocated = allocatedBy(pool, SegmentRange.of(1, 3), 1, "h1", T0, TTL);
            pool.confirm("h1");

            // Asked to free [0,4); only segments 1 and 2 were ever occupied.
            int cleared = pool.releaseConfirmed(SegmentRange.of(0, 4), allocated.berthOrdinals());

            assertEquals(2, cleared, "one berth, two occupied segments - not four");
            assertEquals(1, pool.freeOn(SegmentRange.of(0, 4)));
            pool.checkInvariants();
        }

        /** Unwraps the sealed result; every allocation here is expected to succeed. */
        private AllocationResult.Allocated allocatedBy(
                BerthPool pool,
                SegmentRange range,
                int passengers,
                String holdId,
                Instant now,
                long ttl) {
            return (AllocationResult.Allocated) pool.allocate(range, passengers, holdId, now, ttl);
        }
    }

    @Nested
    @DisplayName("holds and lazy reaping (§9.2)")
    class Holds {

        @Test
        @DisplayName("an expired hold is reaped at the start of the next allocation")
        void expiredHoldsAreReapedLazily() {
            var pool = new BerthPool(1, 4);
            var range = SegmentRange.of(0, 4);

            allocated(pool.allocate(range, 1, "first", T0, TTL));
            assertEquals(0, pool.freeOn(range), "the only berth is held");

            // Same instant: the hold is live, so the second request must fail.
            assertInstanceOf(
                    AllocationResult.Unavailable.class,
                    pool.allocate(range, 1, "second", T0, TTL));

            // After the TTL: the lazy reap inside allocate frees it first.
            // Without that reap a genuinely free berth reads as taken, and the
            // system refuses a booking it should accept with no error anywhere.
            var later = T0.plusMillis(TTL + 1);
            var third = allocated(pool.allocate(range, 1, "third", T0.plusMillis(TTL + 1), TTL));

            assertEquals(List.of(0), third.berthOrdinals());
            assertEquals(1, pool.liveHoldCount(), "the expired hold is gone, the new one remains");
            pool.checkInvariants();
            assertTrue(later.isAfter(T0));
        }

        @Test
        @DisplayName("a hold expiring exactly now is treated as expired")
        void expiryBoundaryIsInclusive() {
            // The boundary has to be picked. Picking "expired" fails toward
            // releasing inventory rather than toward holding it, which is the
            // safer direction for a system whose worst outcome is berths nobody
            // can sell.
            var pool = new BerthPool(1, 2);
            allocated(pool.allocate(SegmentRange.of(0, 2), 1, "h", T0, TTL));

            assertEquals(1, pool.reapExpired(T0.plusMillis(TTL)));
            assertEquals(0, pool.liveHoldCount());
        }

        @Test
        @DisplayName("release is idempotent and reports whether it did anything")
        void releaseIsIdempotent() {
            var pool = new BerthPool(2, 2);
            allocated(pool.allocate(SegmentRange.of(0, 2), 1, "h", T0, TTL));

            assertTrue(pool.release("h"), "first release finds the hold");
            assertFalse(pool.release("h"), "second is a no-op, not an error");
            assertFalse(pool.release("never-existed"));

            assertEquals(2, pool.remainingBerths());
            pool.checkInvariants();
        }

        @Test
        @DisplayName("reusing a live hold id is rejected")
        void duplicateHoldIdIsRejected() {
            // The hold id IS the idempotency key end-to-end (DD-009). Silently
            // overwriting one would orphan the berths the first hold took.
            var pool = new BerthPool(4, 2);
            allocated(pool.allocate(SegmentRange.of(0, 2), 1, "dup", T0, TTL));

            assertThrows(
                    IllegalStateException.class,
                    () -> pool.allocate(SegmentRange.of(0, 2), 1, "dup", T0, TTL));
        }

        @Test
        @DisplayName("reaping is safe when there is nothing to reap")
        void reapOnEmptyPool() {
            var pool = new BerthPool(4, 2);
            assertEquals(0, pool.reapExpired(T0.plusMillis(999_999)));
            pool.checkInvariants();
        }
    }

    @Nested
    @DisplayName("snapshots and validation")
    class SnapshotsAndValidation {

        @Test
        @DisplayName("snapshotMasks returns a copy, not the live array")
        void snapshotIsACopy() {
            // Strategy B checkpoints this off the consumer thread. A shared array
            // tears mid-mutation and the snapshot then describes a state that
            // never existed (DD-013).
            var pool = new BerthPool(3, 4);
            long[] before = pool.snapshotMasks();

            allocated(pool.allocate(SegmentRange.of(0, 4), 1, "h", T0, TTL));

            assertEquals(SegmentMask.EMPTY, before[0], "the snapshot must not have changed");
            assertEquals(SegmentRange.of(0, 4).mask(), pool.snapshotMasks()[0]);
        }

        @Test
        @DisplayName("a range longer than the route is rejected")
        void rejectsRangeBeyondRoute() {
            var pool = new BerthPool(4, 4);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> pool.allocate(SegmentRange.of(0, 8), 1, "h", T0, TTL));
        }

        @Test
        @DisplayName("a zero-passenger booking is rejected")
        void rejectsZeroPassengers() {
            var pool = new BerthPool(4, 4);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> pool.allocate(SegmentRange.of(0, 4), 0, "h", T0, TTL));
        }

        @Test
        @DisplayName("an empty pool is legal and allocates nothing")
        void emptyPoolIsLegal() {
            // A class with no berths in a quota is a real configuration, not an
            // error - FR-9's minimum of 1 applies to TATKAL, and a pool can be
            // fully consumed.
            var pool = new BerthPool(0, 4);
            assertEquals(0, pool.remainingBerths());
            assertInstanceOf(
                    AllocationResult.Unavailable.class,
                    pool.allocate(SegmentRange.of(0, 4), 1, "h", T0, TTL));
            pool.checkInvariants();
        }
    }
}
