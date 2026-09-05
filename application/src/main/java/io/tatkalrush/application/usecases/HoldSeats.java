package io.tatkalrush.application.usecases;

import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.IdempotencyStore;
import io.tatkalrush.application.ports.ScheduleQuery;
import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.domain.booking.Passenger;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TatkalWindow;
import io.tatkalrush.domain.pricing.FareCalculator;
import java.time.Instant;

/**
 * {@code API-4}: allocate berths and open a hold (FR-16 to FR-20).
 *
 * <p>This is where the pieces meet — allocator, idempotency, fare, Tatkal window —
 * and the ordering between them is the substance of the class.
 *
 * <h2>Order of operations, and why</h2>
 *
 * <ol>
 *   <li><b>Cheap rejections first</b> — unknown pool, charted schedule, Tatkal
 *       window closed. None of these allocate or write, so doing them before the
 *       idempotency claim avoids consuming a key for a request that cannot
 *       succeed. A client retrying the same key once the window opens then wins
 *       it cleanly.
 *   <li><b>Claim the idempotency key</b>, before allocating (FR-19). Concurrent
 *       duplicates block on the primary key and resolve to the winner's booking
 *       rather than allocating a second set of berths.
 *   <li><b>FR-20's hold cap</b>, after the claim so a retry of an accepted
 *       request is not counted against the caller twice.
 *   <li><b>Allocate</b>, then persist the booking, then complete the claim — all
 *       inside one transaction.
 * </ol>
 *
 * <h2>The part that cannot be atomic</h2>
 *
 * <p>The allocation happens in Redis (Strategy A) or on a partition owner
 * (Strategy B); the booking row happens in Postgres. No transaction spans both.
 * If the insert fails after berths were taken, this <b>compensates</b> by
 * releasing the hold explicitly, then rethrows.
 *
 * <p>A crash between those two points still leaks a hold, and nothing here can
 * prevent that. What bounds it is the TTL: the berths return within FR-17's 120
 * seconds whether or not anything cleans up. That is the failure the hold TTL
 * exists for, and it is worth being explicit that the compensation is an
 * optimisation over waiting, not a correctness guarantee.
 */
public final class HoldSeats {

    private final SeatAllocator allocator;
    private final IdempotencyStore idempotency;
    private final BookingRepository bookings;
    private final ScheduleQuery schedules;
    private final UnitOfWork unitOfWork;
    private final long holdTtlMillis;
    private final int maxActiveHolds;

    public HoldSeats(
            SeatAllocator allocator,
            IdempotencyStore idempotency,
            BookingRepository bookings,
            ScheduleQuery schedules,
            UnitOfWork unitOfWork,
            long holdTtlMillis,
            int maxActiveHolds) {
        this.allocator = allocator;
        this.idempotency = idempotency;
        this.bookings = bookings;
        this.schedules = schedules;
        this.unitOfWork = unitOfWork;
        this.holdTtlMillis = holdTtlMillis;
        this.maxActiveHolds = maxActiveHolds;
    }

    public Result handle(HoldSeatsCommand command) {
        var pool = schedules.findPool(command.pool());
        if (pool.isEmpty()) {
            return new Result.UnknownPool(command.pool());
        }
        var descriptor = pool.get();

        // Booking is closed once the chart is prepared (§11.2 CHART_PREPARED).
        if (descriptor.chartPrepared()) {
            return new Result.ChartPrepared();
        }

        // FR-29: a locked Tatkal pool reports WHEN it opens, not merely that it
        // is shut. A client that knows the instant can wait for it; one that does
        // not will poll, which is the herd FR-30 exists to avoid.
        if (!TatkalWindow.isPoolOpen(
                command.pool().quotaType(),
                descriptor.journeyDate(),
                command.pool().travelClass(),
                command.now())) {
            return new Result.QuotaLocked(
                    TatkalWindow.opensAt(
                            descriptor.journeyDate(), command.pool().travelClass()));
        }

        if (command.range().toSeq() > descriptor.segmentCount()) {
            return new Result.InvalidRange(command.range(), descriptor.segmentCount());
        }

        return unitOfWork.inTransaction(
                () -> holdWithinTransaction(command, descriptor), HoldSeats::mustNotPersist);
    }

    /**
     * Correct outcomes that must nonetheless leave no trace.
     *
     * <p>All three consumed an idempotency claim of our own and then declined to
     * produce a booking. The rollback is what releases that claim, so the client
     * may retry with the <em>same</em> key — against a train that has since freed a
     * berth (FR-51), once the Tatkal window opens (FR-29), or after one of its own
     * holds expires (FR-20). Committing any of them burns the key permanently and
     * turns a temporary "not now" into a permanent 409.
     *
     * <p>The claim outcomes are deliberately absent. {@code Reused},
     * {@code RetryLater} and {@code DuplicateRequest} all mean we did <em>not</em>
     * win the claim, so there is nothing of ours to release.
     */
    private static boolean mustNotPersist(Result result) {
        return result instanceof Result.SeatUnavailable
                || result instanceof Result.QuotaLocked
                || result instanceof Result.TooManyHolds;
    }

    private Result holdWithinTransaction(
            HoldSeatsCommand command, ScheduleQuery.PoolDescriptor descriptor) {

        var claim =
                idempotency.claim(command.idempotencyKey(), command.userId(), command.requestHash());

        if (claim instanceof IdempotencyStore.Claim.Reused reused) {
            return new Result.IdempotencyKeyReused(reused.existingRequestHash());
        }
        if (claim instanceof IdempotencyStore.Claim.Pending) {
            // The winner is still working, or failed after claiming. Either way
            // this caller must not allocate. FR-19 maps it to the existing
            // RETRY_LATER path rather than a new error, so the client retries
            // with the SAME key and converges instead of allocating again.
            return new Result.RetryLater();
        }
        if (claim instanceof IdempotencyStore.Claim.Duplicate duplicate) {
            // FR-19: the CURRENT representation, re-rendered from booking state,
            // never a stored copy of the original response. A frozen 200 replayed
            // at t=300 s would assert a hold that expired at t=120 s.
            return bookings
                    .findById(duplicate.bookingId())
                    .<Result>map(Result.DuplicateRequest::new)
                    .orElseGet(Result.RetryLater::new);
        }

        // FR-20, checked after the claim so a retry of an already-accepted
        // request does not count against the caller a second time.
        int activeHolds = bookings.countActiveHolds(command.userId(), command.now());
        if (activeHolds >= maxActiveHolds) {
            return new Result.TooManyHolds(activeHolds, maxActiveHolds);
        }

        // FR-67a: computed ONCE, here, and frozen onto the booking. Never
        // recomputed at confirm, cancel or chart - a rate-table edit would
        // otherwise change the expected value for every historical booking and
        // break INV-7 across the whole dataset at once.
        long farePaise =
                FareCalculator.farePaise(
                        schedules.distanceKm(command.pool().scheduleId(), command.range()),
                        command.pool().travelClass(),
                        command.pool().quotaType(),
                        command.passengerCount());

        var allocation =
                allocator.allocate(
                        new AllocationRequest(
                                command.pool(),
                                command.range(),
                                command.passengerCount(),
                                command.idempotencyKey(),
                                command.now(),
                                holdTtlMillis));

        if (allocation instanceof AllocationResult.Unavailable unavailable) {
            // Not an error (FR-51). Returning here rolls the transaction back,
            // which releases the idempotency claim - so the client may retry the
            // same key against a train that has since freed a berth.
            return new Result.SeatUnavailable(
                    unavailable.available(), unavailable.requested());
        }
        if (allocation instanceof AllocationResult.QuotaLocked locked) {
            return new Result.QuotaLocked(locked.opensAt());
        }

        var allocated = (AllocationResult.Allocated) allocation;

        try {
            long bookingId =
                    bookings.createHeld(
                            new BookingRepository.NewHeldBooking(
                                    command.pool(),
                                    command.range(),
                                    command.passengers(),
                                    farePaise,
                                    command.userId(),
                                    allocated.expiresAt(),
                                    command.idempotencyKey(),
                                    allocated.berthIds()));

            idempotency.complete(command.idempotencyKey(), bookingId);

            return new Result.Held(
                    bookingId,
                    allocated.berthIds(),
                    allocated.expiresAt(),
                    farePaise,
                    command.pool(),
                    command.range());

        } catch (RuntimeException e) {
            // Compensation, not atomicity. The berths live in Redis and the
            // booking in Postgres, and no transaction spans both - so a failure
            // here would otherwise leave berths held for a booking that does not
            // exist. Releasing explicitly returns them immediately.
            //
            // A crash between the allocate and this release still leaks the hold,
            // and nothing here can prevent that. The TTL bounds it: the berths
            // come back within FR-17's 120 seconds regardless. This is an
            // optimisation over waiting, not a correctness guarantee, and it is
            // worth being clear which.
            allocator.release(command.idempotencyKey());
            throw e;
        }
    }

    /** What a hold attempt produced. Maps onto §11.2's error codes. */
    public sealed interface Result {

        /** Berths held. {@code 201} with the hold's expiry. */
        record Held(
                long bookingId,
                java.util.List<Long> berthIds,
                Instant expiresAt,
                long farePaise,
                io.tatkalrush.domain.inventory.PoolKey pool,
                SegmentRange range)
                implements Result {}

        /** {@code 200 DUPLICATE_REQUEST} — FR-19 replay, current representation. */
        record DuplicateRequest(BookingRepository.BookingView booking) implements Result {}

        /** {@code 409 SEAT_UNAVAILABLE} — a correct outcome, not an error (FR-51). */
        record SeatUnavailable(int available, int requested) implements Result {}

        /** {@code 409 QUOTA_LOCKED} — the Tatkal window has not opened (FR-29). */
        record QuotaLocked(Instant opensAt) implements Result {}

        /** {@code 409 IDEMPOTENCY_KEY_REUSED} — same key, different body (FR-19). */
        record IdempotencyKeyReused(String existingRequestHash) implements Result {}

        /** {@code 503 RETRY_LATER} — a claim is in flight; retry with the same key. */
        record RetryLater() implements Result {}

        /** {@code 429} — FR-20's cap on concurrent holds per caller. */
        record TooManyHolds(int active, int limit) implements Result {}

        /** {@code 409 CHART_PREPARED} — booking is closed for this schedule. */
        record ChartPrepared() implements Result {}

        /** {@code 404} — no such pool. */
        record UnknownPool(io.tatkalrush.domain.inventory.PoolKey pool) implements Result {}

        /** {@code 400} — the range runs past the end of the route. */
        record InvalidRange(SegmentRange range, int segmentCount) implements Result {}
    }

    /** One hold attempt. */
    public record HoldSeatsCommand(
            io.tatkalrush.domain.inventory.PoolKey pool,
            SegmentRange range,
            java.util.List<Passenger> passengers,
            long userId,
            String idempotencyKey,
            String requestHash,
            Instant now) {

        public HoldSeatsCommand {
            passengers = java.util.List.copyOf(passengers);
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                // FR-19 makes the header mandatory. Generating one server-side
                // would defeat the point: the client's retry would carry a
                // different key and allocate a second set of berths.
                throw new IllegalArgumentException("Idempotency-Key is required (FR-19)");
            }
            if (pool.quotaType() == null) {
                throw new IllegalArgumentException("quotaType is required");
            }
            if (passengers.isEmpty() || passengers.size() > 6) {
                throw new IllegalArgumentException(
                        "a booking carries 1..6 passengers, got " + passengers.size());
            }
        }

        /**
         * Derived from {@link #passengers()}, never carried alongside it.
         *
         * <p>Two fields that must agree are two chances to disagree, and the
         * {@code passenger_count} CHECK would report that disagreement as a
         * constraint failure rather than as the modelling error it is.
         */
        public int passengerCount() {
            return passengers.size();
        }

        public boolean isTatkal() {
            return pool.quotaType() == QuotaType.TATKAL;
        }
    }
}
