package io.tatkalrush.application.ports;

import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.booking.Passenger;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence for bookings. */
public interface BookingRepository {

    /**
     * A booking as the hold path needs to see it.
     *
     * @param pnr absent until confirmation (§6.4, DD-032). A held booking has no
     *     PNR, and issuing one per hold would burn a sequence value for every
     *     hold that expires unpaid — during a spike, nearly all of them.
     */
    record BookingView(
            long id,
            Optional<String> pnr,
            BookingStatus status,
            PoolKey pool,
            SegmentRange range,
            int passengerCount,
            long farePaise,
            long userId,
            Optional<Instant> holdExpiresAt,
            List<Long> berthIds) {}

    /**
     * A new booking in {@link BookingStatus#HELD}.
     *
     * @param passengers one per berth, in the same order. {@code passengers.berth_id}
     *     is the only column where a held berth can live, so a booking cannot be
     *     stored without them — which is why they are here rather than a separate
     *     later write.
     * @param berthIds must be the same length as {@code passengers}; berth
     *     {@code i} is assigned to passenger {@code i}
     */
    record NewHeldBooking(
            PoolKey pool,
            SegmentRange range,
            List<Passenger> passengers,
            long farePaise,
            long userId,
            Instant holdExpiresAt,
            String idempotencyKey,
            List<Long> berthIds) {

        public NewHeldBooking {
            passengers = List.copyOf(passengers);
            berthIds = List.copyOf(berthIds);
            if (passengers.size() != berthIds.size()) {
                throw new IllegalArgumentException(
                        "%d passengers but %d berths — FR-6 allocates all or nothing"
                                .formatted(passengers.size(), berthIds.size()));
            }
        }

        /** Derived, never stored twice. */
        public int passengerCount() {
            return passengers.size();
        }
    }

    /** Inserts a held booking and returns its id. */
    long createHeld(NewHeldBooking booking);

    Optional<BookingView> findById(long bookingId);

    /**
     * Loads a booking and locks its row for the rest of the transaction
     * ({@code SELECT ... FOR UPDATE}).
     *
     * <p>Used by the confirmation path, where it is <b>required for correctness,
     * not for tidiness</b>. FR-22's webhook and FR-23's reconciliation poll are
     * two independent routes to the same settlement, and they can arrive at once.
     * Without the lock both read {@code PAYMENT_PENDING}, both insert
     * {@code seat_allocations} — and the second insert overlaps the <em>first
     * one's own rows</em>. The exclusion constraint fires, and a race we failed to
     * serialise reports itself as {@code ALLOCATION_CONFLICT}: the one signal
     * INV-11 exists to trust.
     *
     * <p>Concurrent callers block here rather than failing, which is the same
     * "block, don't fail" property that makes insert-first idempotency work.
     */
    Optional<BookingView> findByIdForUpdate(long bookingId);

    /**
     * FR-20: holds a user currently has open.
     *
     * <p>Counted from {@code hold_expires_at} in Postgres rather than from Redis.
     * A cap enforced against a cache would stop being enforced the moment chaos
     * scenario C2 flushes it — and C2 runs during P2, concurrently with live
     * traffic.
     */
    int countActiveHolds(long userId, Instant now);

    /** Outcome of FR-25 step 2, the {@code seat_allocations} insert. */
    sealed interface AllocationOutcome {

        /** Rows written. The allocation is now durable and constraint-checked. */
        record Persisted() implements AllocationOutcome {}

        /**
         * {@code no_overlapping_allocations} refused the write — SQLState
         * {@code 23P01}.
         *
         * <p>Only ever produced <em>after</em> the hold was verified live, so it
         * carries exactly one meaning: an allocator handed one berth to two
         * bookings. See {@link io.tatkalrush.domain.pricing.RefundReason#ALLOCATION_CONFLICT}.
         */
        record Conflict(long berthId) implements AllocationOutcome {}

        /**
         * This booking's rows were already there, found by the pre-insert check.
         *
         * <p>Means a duplicate confirmation reached this point despite the row
         * lock — a concurrency bug in <em>this</em> layer, and emphatically not an
         * allocator defect. Separating it is what keeps {@link Conflict}
         * unambiguous.
         */
        record AlreadyPresent() implements AllocationOutcome {}
    }

    /**
     * FR-25 step 2: writes the durable allocation rows.
     *
     * <p><b>Implementor contract, part one: check before inserting.</b> Query for
     * this booking's existing {@code seat_allocations} rows first and return
     * {@link AllocationOutcome.AlreadyPresent} if any exist. This is check-then-act
     * — normally a defect, and safe here <em>only</em> because the caller holds the
     * booking's row lock from {@link #findByIdForUpdate}. Remove that lock and this
     * check becomes a race.
     *
     * <p>The check is not optional, because the database cannot make this
     * distinction for us. A duplicate confirmation's second insert overlaps its own
     * first rows, so {@code no_overlapping_allocations} rejects it with SQLState
     * {@code 23P01} — byte-for-byte the error an allocator defect produces. A
     * {@code UNIQUE (booking_id, berth_id)} key was tried and does not help:
     * Postgres checks indexes in OID order, the older GiST index reports first, and
     * the unique key never fires. Which constraint wins is an artefact of creation
     * order, not a contract. {@code SchemaMigrationTest} pins this behaviour so the
     * reasoning survives.
     *
     * <p><b>Implementor contract, part two: return, do not throw.</b> In Postgres a
     * failed statement poisons the enclosing transaction — every subsequent
     * statement fails until rollback — so the insert must be wrapped in a
     * {@code SAVEPOINT} that is rolled back on violation, or the caller cannot act
     * on the answer it was just given.
     */
    AllocationOutcome persistAllocations(
            long bookingId, long scheduleId, SegmentRange range, List<Long> berthIds);

    /**
     * FR-21: {@code HELD → PAYMENT_PENDING}, as a compare-and-set.
     *
     * <p>This transition <b>is</b> API-5's idempotency. A second initiation finds
     * the booking already in {@code PAYMENT_PENDING} and returns the existing
     * payment rather than opening a second charge, so no {@code Idempotency-Key}
     * header is needed here — when the state machine already admits exactly one
     * caller, a key adds ceremony and a second thing to keep consistent.
     *
     * @return whether this caller performed the transition
     */
    boolean beginPayment(long bookingId, Instant at);

    /**
     * {@code PAYMENT_PENDING → FAILED} (FR-27), as a compare-and-set.
     *
     * <p>Distinct from {@link #markFailedRefunded}: {@code FAILED} means nothing
     * was ever captured, so nothing is owed back. Routing a declined charge
     * through the refund path would compute a refund against money that never
     * moved.
     */
    boolean markFailed(long bookingId, Instant at);

    /**
     * FR-25 step 3: {@code PAYMENT_PENDING → CONFIRMED} with a PNR, as one
     * compare-and-set.
     *
     * <p>{@code UPDATE ... WHERE status = 'PAYMENT_PENDING'}: zero rows updated
     * means someone else settled this booking first, and the caller must not
     * proceed as though it had won.
     *
     * @return whether this caller performed the transition
     */
    boolean confirm(long bookingId, String pnr, Instant confirmedAt);

    /**
     * {@code PAYMENT_PENDING → FAILED_REFUNDED} (FR-24, FR-25), as a
     * compare-and-set on the same terms as {@link #confirm}.
     *
     * <p>Applied when the refund intent is recorded rather than when the money
     * arrives back: the berth is definitively not this customer's the moment we
     * decide to refund, and leaving the booking in {@code PAYMENT_PENDING} until
     * the PSP answers would let FR-23 pick it up and try to settle it again.
     */
    boolean markFailedRefunded(long bookingId, Instant at);
}
