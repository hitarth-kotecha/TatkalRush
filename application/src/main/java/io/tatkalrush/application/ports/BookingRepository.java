package io.tatkalrush.application.ports;

import io.tatkalrush.domain.booking.BookingStatus;
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

    /** A new booking in {@link BookingStatus#HELD}. */
    record NewHeldBooking(
            PoolKey pool,
            SegmentRange range,
            int passengerCount,
            long farePaise,
            long userId,
            Instant holdExpiresAt,
            String idempotencyKey,
            List<Long> berthIds) {}

    /** Inserts a held booking and returns its id. */
    long createHeld(NewHeldBooking booking);

    Optional<BookingView> findById(long bookingId);

    /**
     * FR-20: holds a user currently has open.
     *
     * <p>Counted from {@code hold_expires_at} in Postgres rather than from Redis.
     * A cap enforced against a cache would stop being enforced the moment chaos
     * scenario C2 flushes it — and C2 runs during P2, concurrently with live
     * traffic.
     */
    int countActiveHolds(long userId, Instant now);
}
