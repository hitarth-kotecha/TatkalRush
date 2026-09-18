package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.application.ports.ConfirmResult;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PartitionOwner} in isolation, sequentially, with no Kafka.
 *
 * <p>Not the allocator contract suite: that runs against {@link KafkaSeatAllocator}
 * (AC-1.6/AC-2.1) because {@link PartitionOwner} is not itself safe to call
 * concurrently — T-1's 500-thread race would just corrupt it, which is the point
 * of the Javadoc warning. This class checks the pool/hold bookkeeping this class
 * adds on top of {@code BerthPool}, one call at a time.
 */
class PartitionOwnerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final long TTL = 120_000;

    private final PartitionOwner owner = new PartitionOwner();

    private PoolKey pool(long scheduleId, int berthCount, int segmentCount, long... berthIds) {
        var key = new PoolKey(scheduleId, TravelClass.SL, QuotaType.GENERAL);
        owner.provision(key, berthCount, segmentCount, java.util.Arrays.stream(berthIds).boxed().toList());
        return key;
    }

    private AllocationResult.Allocated allocate(
            PoolKey pool, int from, int to, int passengers, String holdId) {
        var request =
                new AllocationRequest(pool, SegmentRange.of(from, to), passengers, holdId, T0, TTL);
        return assertInstanceOf(AllocationResult.Allocated.class, owner.allocate(request));
    }

    @Test
    @DisplayName("allocate reports database berth ids in ordinal order, not pool ordinals")
    void allocateReportsBerthIds() {
        var pool = pool(1, 2, 4, 100L, 101L);
        var result = allocate(pool, 0, 4, 2, "h1");

        assertEquals(List.of(100L, 101L), result.berthIds());
        assertEquals(T0.plusMillis(TTL), result.expiresAt());
    }

    @Test
    @DisplayName("a second passenger group cannot overlap the first's segment")
    void overlappingRangeIsUnavailable() {
        var pool = pool(2, 1, 4, 200L);
        allocate(pool, 0, 2, 1, "a");

        var result = owner.allocate(new AllocationRequest(pool, SegmentRange.of(1, 3), 1, "b", T0, TTL));
        assertInstanceOf(AllocationResult.Unavailable.class, result);
    }

    @Test
    @DisplayName("release frees the berth and is idempotent, including for an unknown hold")
    void releaseIsIdempotent() {
        var pool = pool(3, 1, 4, 300L);
        allocate(pool, 0, 4, 1, "h");

        owner.release("h");
        owner.release("h"); // already gone
        owner.release("never-existed");

        var free = owner.availability(pool, SegmentRange.of(0, 4)).freeBerths();
        assertEquals(1, free);
    }

    @Test
    @DisplayName("confirm promotes a live hold and reports HoldExpired for a lapsed one")
    void confirmDistinguishesLiveFromExpired() {
        var pool = pool(4, 2, 4, 400L, 401L);
        allocate(pool, 0, 4, 1, "live");
        allocate(pool, 0, 4, 1, "gone");
        owner.release("gone");

        var confirmed = assertInstanceOf(ConfirmResult.Confirmed.class, owner.confirm("live", 9L));
        assertEquals(9L, confirmed.bookingId());
        assertEquals(List.of(400L), confirmed.berthIds());

        assertInstanceOf(ConfirmResult.HoldExpired.class, owner.confirm("gone", 10L));
        assertInstanceOf(ConfirmResult.HoldExpired.class, owner.confirm("never-existed", 11L));
    }

    @Test
    @DisplayName("a confirmed booking's berth survives reapExpired, and cancellation frees it by id")
    void releaseConfirmedFreesAPaidBerth() {
        var pool = pool(5, 1, 4, 500L);
        allocate(pool, 0, 4, 1, "paid");
        owner.confirm("paid", 1L);

        assertEquals(0, owner.reapExpired(T0.plusMillis(TTL * 10)), "a paid berth is never reaped");
        assertEquals(0, owner.availability(pool, SegmentRange.of(0, 4)).freeBerths());

        // 4 bits cleared, not 1 berth: releaseConfirmed counts (berth, segment)
        // pairs, and this berth was occupied across all 4 segments of the range.
        int cleared = owner.releaseConfirmed(pool, SegmentRange.of(0, 4), List.of(500L));
        assertEquals(4, cleared);
        assertEquals(1, owner.availability(pool, SegmentRange.of(0, 4)).freeBerths());

        assertEquals(0, owner.releaseConfirmed(pool, SegmentRange.of(0, 4), List.of(500L)), "idempotent");
    }

    @Test
    @DisplayName("reapExpired covers every pool this owner holds, and expiry is inclusive")
    void reapExpiredCoversEveryOwnedPool() {
        var first = pool(6, 1, 4, 600L);
        var second = pool(7, 1, 4, 700L);
        allocate(first, 0, 4, 1, "a");
        allocate(second, 0, 4, 1, "b");

        assertEquals(2, owner.reapExpired(T0.plusMillis(TTL)), "expiry is inclusive");
        assertEquals(1, owner.availability(first, SegmentRange.of(0, 4)).freeBerths());
        assertEquals(1, owner.availability(second, SegmentRange.of(0, 4)).freeBerths());
        assertEquals(0, owner.reapExpired(T0.plusMillis(TTL)), "second sweep finds nothing");
    }

    // -------------------------------------------------- replay/checkpoint (M3)

    @Test
    @DisplayName("applyEvent replays a full history onto a fresh owner and reproduces the same state")
    void applyEventReplaysFullHistory() {
        var pool = pool(8, 2, 4, 800L, 801L);
        allocate(pool, 0, 2, 1, "keep"); // survives to the end
        var doomed = allocate(pool, 2, 4, 1, "release-me");
        owner.release("release-me");
        var paid = allocate(pool, 0, 4, 1, "pay-me"); // will conflict with nothing: different berth
        owner.confirm("pay-me", 42L);

        var events =
                List.of(
                        new PartitionEvent.Provisioned("c0", pool, 2, 4, List.of(800L, 801L), List.of()),
                        new PartitionEvent.Allocated(
                                "keep", pool, "keep", SegmentRange.of(0, 2), List.of(800L), T0.plusMillis(TTL)),
                        new PartitionEvent.Allocated(
                                "release-me",
                                pool,
                                "release-me",
                                SegmentRange.of(2, 4),
                                doomed.berthIds(),
                                T0.plusMillis(TTL)),
                        new PartitionEvent.Released("r1", pool, "release-me"),
                        new PartitionEvent.Allocated(
                                "pay-me", pool, "pay-me", SegmentRange.of(0, 4), paid.berthIds(), T0.plusMillis(TTL)),
                        new PartitionEvent.Confirmed("cf1", pool, "pay-me", 42L, paid.berthIds()));

        var replayed = new PartitionOwner();
        for (PartitionEvent event : events) {
            replayed.applyEvent(event);
        }

        assertEquals(
                owner.availability(pool, SegmentRange.of(0, 4)).freeBerths(),
                replayed.availability(pool, SegmentRange.of(0, 4)).freeBerths());
        assertInstanceOf(ConfirmResult.Confirmed.class, replayed.confirm("keep", 1L));
        assertInstanceOf(ConfirmResult.HoldExpired.class, replayed.confirm("release-me", 2L));
    }

    @Test
    @DisplayName("snapshotOf/restorePool round-trips a pool's masks and live holds exactly")
    void checkpointRoundTrip() {
        var pool = pool(9, 2, 4, 900L, 901L);
        allocate(pool, 0, 2, 1, "live");
        var confirmed = allocate(pool, 2, 4, 1, "paid");
        owner.confirm("paid", 5L);

        byte[] snapshot = owner.snapshotOf(pool);

        var restored = new PartitionOwner();
        restored.provision(pool, 2, 4, List.of(900L, 901L)); // berthIds don't travel in the checkpoint
        restored.restorePool(pool, snapshot);

        assertEquals(
                owner.availability(pool, SegmentRange.of(0, 4)).freeBerths(),
                restored.availability(pool, SegmentRange.of(0, 4)).freeBerths());
        assertInstanceOf(ConfirmResult.Confirmed.class, restored.confirm("live", 1L));
        assertInstanceOf(ConfirmResult.HoldExpired.class, restored.confirm("paid", 2L), "paid: no live hold, by design");
    }

    // ------------------------------------------------ provisioning (M7)

    @Test
    @DisplayName("provisioning with occupied masks starts the pool already sold, not empty")
    void provisionWithOccupiedMasksStartsSold() {
        var pool = new PoolKey(10, TravelClass.SL, QuotaType.GENERAL);
        // Berth 0 occupied on segments 0-1 (mask 0b0011), berth 1 fully free.
        owner.provision(pool, 2, 4, List.of(1000L, 1001L), List.of(0b0011L, 0L));

        assertEquals(1, owner.availability(pool, SegmentRange.of(0, 2)).freeBerths(), "berth 0 is taken there");
        assertEquals(2, owner.availability(pool, SegmentRange.of(2, 4)).freeBerths(), "neither berth touches segments 2-3");

        // No hold record exists for the pre-occupied berth - it is confirmed
        // occupancy, exactly like BerthPool.confirm() leaves behind, not a hold
        // provisioning invented.
        assertInstanceOf(ConfirmResult.HoldExpired.class, owner.confirm("whatever-hold-id-would-be", 1L));
    }

    @Test
    @DisplayName("provisioning rejects an occupied-masks list sized wrong for the berth count")
    void provisionRejectsMismatchedOccupiedMasksSize() {
        var pool = new PoolKey(11, TravelClass.SL, QuotaType.GENERAL);
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.provision(pool, 2, 4, List.of(1100L, 1101L), List.of(0L)));
    }

    @Test
    @DisplayName("replaying a Provisioned event with occupied masks reproduces the same occupancy")
    void replayReproducesOccupiedMasksFromProvisioning() {
        var pool = new PoolKey(12, TravelClass.SL, QuotaType.GENERAL);
        var event =
                new PartitionEvent.Provisioned(
                        "c0", pool, 2, 4, List.of(1200L, 1201L), List.of(0b1111L, 0L));

        var replayed = new PartitionOwner();
        replayed.applyEvent(event);

        // Berth 0 fully occupied, berth 1 fully free - one of two berths free on
        // every segment.
        assertEquals(1, replayed.availability(pool, SegmentRange.of(0, 4)).freeBerths());
    }
}
