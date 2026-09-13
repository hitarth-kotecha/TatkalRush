package io.tatkalrush.application.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.HoldExpiry;
import io.tatkalrush.application.ports.InMemorySeatAllocator;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpireHoldsTest {

    private static final Instant T0 = Instant.parse("2026-10-20T04:30:00Z");
    private static final long TTL = 15_000;

    private final InMemorySeatAllocator allocator = new InMemorySeatAllocator();
    private final PoolKey pool =
            allocator.provision(new PoolKey(21, TravelClass.SL, QuotaType.TATKAL), 2, 4);

    @Test
    void bothHalvesRunInOneSweep() {
        allocator.allocate(new AllocationRequest(pool, SegmentRange.of(0, 4), 1, "h", T0, TTL));
        var expiry = new ScriptedExpiry(3);

        var report = new ExpireHolds(allocator, expiry).sweep(T0.plusMillis(TTL));

        assertEquals(1, report.holdsReaped());
        assertEquals(3, report.bookingsExpired());
        assertFalse(report.backlogRemains());
        assertEquals(List.of(T0.plusMillis(TTL)), expiry.calledWith, "one instant for both halves");
    }

    @Test
    void aBacklogIsDrainedInBatchesUntilAShortOne() {
        var expiry = new ScriptedExpiry(ExpireHolds.BATCH, ExpireHolds.BATCH, 7);

        var report = new ExpireHolds(allocator, expiry).sweep(T0);

        assertEquals(ExpireHolds.BATCH * 2 + 7, report.bookingsExpired());
        assertEquals(3, expiry.calledWith.size());
        assertFalse(report.backlogRemains());
    }

    /**
     * An outage's backlog must not become one sweep long enough to overlap the next
     * and compete with live traffic for connections. It is reported instead, so the
     * scheduler can say the reaper is behind.
     */
    @Test
    void oneSweepIsBoundedAndSaysWhenItStoppedShort() {
        var full = new int[ExpireHolds.MAX_BATCHES + 5];
        java.util.Arrays.fill(full, ExpireHolds.BATCH);
        var expiry = new ScriptedExpiry(full);

        var report = new ExpireHolds(allocator, expiry).sweep(T0);

        assertEquals(ExpireHolds.MAX_BATCHES, expiry.calledWith.size());
        assertTrue(report.backlogRemains());
    }

    /**
     * Failure propagates. The scheduler owns "one bad sweep must not stop the
     * next"; the use case swallowing it would make a broken reaper indistinguishable
     * from an idle one.
     */
    @Test
    void aFailureIsNotSwallowed() {
        HoldExpiry broken =
                (now, limit) -> {
                    throw new IllegalStateException("connection refused");
                };

        assertThrows(
                IllegalStateException.class, () -> new ExpireHolds(allocator, broken).sweep(T0));
    }

    private static final class ScriptedExpiry implements HoldExpiry {
        private final int[] answers;
        final List<Instant> calledWith = new ArrayList<>();

        ScriptedExpiry(int... answers) {
            this.answers = answers;
        }

        @Override
        public int expireLapsed(Instant now, int limit) {
            int i = calledWith.size();
            calledWith.add(now);
            return i < answers.length ? answers[i] : 0;
        }
    }
}
