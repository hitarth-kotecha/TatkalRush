package io.tatkalrush.application.usecases;

import io.tatkalrush.application.ports.HoldExpiry;
import io.tatkalrush.application.ports.SeatAllocator;
import java.time.Instant;

/**
 * §13.2's hold reaper: one sweep, both halves of FR-18.
 *
 * <h2>An optimisation, not a correctness dependency</h2>
 *
 * <p>§13.2 requires this to be said in the code, so it is said first. Allocation
 * reaps its own pool before scanning (§9.2), which means a pool anyone is booking
 * from never keeps a lapsed berth whether or not this ever runs. A stalled,
 * crash-looping or undeployed reaper cannot lose a seat.
 *
 * <p>What it is for is the pool <em>nobody</em> is booking from. A Tatkal spike
 * hits a set of pools for a minute and moves on, and without this every hold left
 * in them stays set until some unrelated booking happens along — search
 * under-reports those pools, their bookings say {@code HELD} indefinitely, and no
 * quiesced invariant check (§14) can ever be run against the system. The first
 * P1 run against a real stack left 1,314 of them.
 *
 * <h2>Two effects, in no particular order</h2>
 *
 * <p>No transaction spans Redis and Postgres, so a crash can land between the two
 * calls. Either order recovers on the next sweep, because each side is decided
 * by its own record and the clock rather than by the other side:
 *
 * <ul>
 *   <li>berths freed, booking still {@code HELD} with a past expiry — the next
 *       sweep's {@link HoldExpiry#expireLapsed} still selects it;
 *   <li>booking {@code EXPIRED}, berths still set — the next sweep's
 *       {@link SeatAllocator#reapExpired}, or any allocation on that pool, still
 *       finds the expired hold in the allocator.
 * </ul>
 *
 * <p>Nothing here stops two replicas sweeping at once, and nothing needs to: both
 * effects are idempotent and each is atomic per pool or per row. The lease the
 * scheduler takes is about not doing the work twice, not about doing it safely.
 */
public final class ExpireHolds {

    /**
     * Postgres rows per statement. Small enough that one {@code UPDATE} holds its
     * row locks for milliseconds; the loop, not the batch, handles a backlog.
     */
    static final int BATCH = 500;

    /**
     * Batches per sweep. Bounds one sweep at 10,000 bookings, so a backlog after an
     * outage is drained over several sweeps rather than in one long pass that
     * overlaps the next and competes with live traffic for connections.
     */
    static final int MAX_BATCHES = 20;

    private final SeatAllocator allocator;
    private final HoldExpiry holdExpiry;

    public ExpireHolds(SeatAllocator allocator, HoldExpiry holdExpiry) {
        this.allocator = allocator;
        this.holdExpiry = holdExpiry;
    }

    /**
     * @param holdsReaped allocator holds released, across every pool
     * @param bookingsExpired Postgres bookings moved {@code HELD → EXPIRED}
     * @param backlogRemains the batch cap was reached; more lapsed bookings exist
     */
    public record Report(int holdsReaped, int bookingsExpired, boolean backlogRemains) {}

    public Report sweep(Instant now) {
        int holds = allocator.reapExpired(now);

        int bookings = 0;
        boolean backlog = true;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int expired = holdExpiry.expireLapsed(now, BATCH);
            bookings += expired;
            if (expired < BATCH) {
                backlog = false;
                break;
            }
        }
        return new Report(holds, bookings, backlog);
    }
}
