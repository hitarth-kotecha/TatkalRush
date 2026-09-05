package io.tatkalrush.application.usecases;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.application.ports.PaymentGateway;
import io.tatkalrush.application.ports.PaymentRepository;
import io.tatkalrush.application.ports.ScheduleQuery;
import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.application.ports.UnitOfWork;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.pricing.RefundPolicy;
import io.tatkalrush.domain.pricing.RefundReason;
import java.time.Instant;

/**
 * {@code API-7}: cancelling a booking (FR-43 to FR-46).
 *
 * <h2>One endpoint, two operations, and the state picks</h2>
 *
 * <ul>
 *   <li>{@code HELD} → a <b>release</b>. The berths go back, the booking becomes
 *       {@code EXPIRED}, and no refund path is involved because no money moved.
 *       FR-27 has no {@code HELD → CANCELLED} edge, and sending one down the refund
 *       path would have {@link RefundPolicy} return 90% of a fare nobody paid.
 *   <li>{@code CONFIRMED} → a <b>cancellation</b>. Berths freed, refund per FR-44
 *       and FR-45, booking becomes {@code CANCELLED}.
 * </ul>
 *
 * <h2>The cancellation commits before the berths are freed</h2>
 *
 * <p>The allocator is Redis and the booking is Postgres. No transaction spans both,
 * so a crash between them leaks something — and the ordering is what decides which:
 *
 * <ul>
 *   <li><b>Free first, then commit.</b> A crash leaves the berth available while
 *       the booking still says {@code CONFIRMED}. Someone buys a seat that is still
 *       sold, and the exclusion constraint catches it at <em>their</em>
 *       confirmation — reported as {@code ALLOCATION_CONFLICT}, failing the run and
 *       accusing the allocator of a bug it did not commit.
 *   <li><b>Commit first, then free.</b> A crash leaves the berth stuck occupied.
 *       Nobody is double-booked, availability is one seat short, and §13.4's
 *       rebuild recovers it because the allocation rows are already gone.
 * </ul>
 *
 * <p>This is the <em>opposite</em> ordering from {@link HoldSeats}, chosen by the
 * same rule. Holding allocates before it persists, leaking a hold the TTL bounds;
 * cancelling persists before it frees, leaking a berth a rebuild fixes. Neither
 * ordering avoids the crash. Both put it on the side that costs availability rather
 * than correctness.
 *
 * <h2>The refund is a percentage of the frozen fare</h2>
 *
 * <p>FR-67a froze {@code fare_paise} at hold time and FR-44's tiers are a
 * percentage of it. Recomputing from the rate table here would make a rate edit
 * retroactively change what every historical booking gets back, and INV-7 — which
 * recomputes the expected value independently — would report the whole dataset as
 * mispriced at once.
 */
public final class CancelBooking {

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final SeatAllocator allocator;
    private final ScheduleQuery schedules;
    private final UnitOfWork unitOfWork;

    public CancelBooking(
            BookingRepository bookings,
            PaymentRepository payments,
            PaymentGateway gateway,
            SeatAllocator allocator,
            ScheduleQuery schedules,
            UnitOfWork unitOfWork) {
        this.bookings = bookings;
        this.payments = payments;
        this.gateway = gateway;
        this.allocator = allocator;
        this.schedules = schedules;
        this.unitOfWork = unitOfWork;
    }

    public Outcome cancel(String pnr, long userId, Instant now) {
        var maybeBooking = bookings.findByPnr(pnr);
        if (maybeBooking.isEmpty()) {
            return new Outcome.UnknownBooking(pnr);
        }
        var booking = maybeBooking.get();

        if (booking.userId() != userId) {
            // Same answer as "no such PNR", deliberately. Telling a caller that a
            // PNR exists but belongs to someone else turns the endpoint into an
            // oracle for enumerating other people's bookings.
            return new Outcome.UnknownBooking(pnr);
        }

        return switch (booking.status()) {
            case HELD -> release(booking, now);
            case CONFIRMED -> cancelConfirmed(booking, now);
            default -> new Outcome.NotCancellable(booking.id(), booking.status());
        };
    }

    /** FR-43's release: no money moved, so no refund path is entered. */
    private Outcome release(BookingRepository.BookingView booking, Instant now) {
        boolean won =
                unitOfWork.inTransaction(() -> bookings.releaseHold(booking.id(), now));
        if (!won) {
            // Lost to the reaper, or to another request. Both mean the hold is
            // already gone, which is the outcome the caller wanted.
            return new Outcome.AlreadyResolved(booking.id());
        }

        // The hold record still exists in the allocator, so release() reaches it -
        // and removes it from the reaper's ZSET, which releaseConfirmed cannot do.
        bookings.holdIdOf(booking.id()).ifPresent(allocator::release);

        return new Outcome.Released(booking.id());
    }

    private Outcome cancelConfirmed(BookingRepository.BookingView booking, Instant now) {
        var descriptor = schedules.findPool(booking.pool());
        if (descriptor.isEmpty()) {
            throw new IllegalStateException(
                    "booking %d references pool %s, which does not exist"
                            .formatted(booking.id(), booking.pool()));
        }

        long refundPaise =
                RefundPolicy.refundOnCancellation(
                        // FR-67a's frozen value, never a fresh calculation.
                        booking.farePaise(),
                        // FR-45: a confirmed TATKAL booking gets nothing back,
                        // whatever the window. RefundPolicy checks that BEFORE the
                        // tier, so a cancellation 72 hours out does not return 90%
                        // of a fare that was never refundable.
                        booking.pool().quotaType(),
                        now,
                        descriptor.get().departureAt());

        var opened =
                unitOfWork.inTransaction(
                        () -> {
                            if (!bookings.cancel(booking.id(), now)) {
                                return null;
                            }
                            // Deleted in the SAME transaction as the transition.
                            // §13.4 rebuilds Redis from these rows, so a cancelled
                            // booking that kept them would have its berth
                            // re-occupied by the next rebuild.
                            bookings.deleteAllocations(booking.id());

                            if (refundPaise == 0) {
                                return new PendingRefund(0, 0, null, 0);
                            }

                            var payment =
                                    payments.findCapturedFor(booking.id())
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "confirmed booking %d has no captured payment"
                                                                            .formatted(booking.id())));

                            long refundId =
                                    payments.openRefund(
                                            booking.id(),
                                            payment.id(),
                                            refundPaise,
                                            RefundReason.CANCELLED);

                            return new PendingRefund(
                                    refundId,
                                    payment.id(),
                                    payment.paymentReference(),
                                    refundPaise);
                        });

        if (opened == null) {
            return new Outcome.AlreadyResolved(booking.id());
        }

        // AFTER the commit. See the class comment: a crash here leaves a berth
        // stuck rather than a seat sold twice.
        allocator.releaseConfirmed(booking.pool(), booking.range(), booking.berthIds());

        if (opened.refundId() == 0) {
            // FR-45's TATKAL case, or FR-44's under-12-hours tier. The booking is
            // cancelled and the berth is back; nothing is owed.
            return new Outcome.Cancelled(booking.id(), 0, RefundSettlement.NOT_OWED);
        }

        return new Outcome.Cancelled(
                booking.id(), opened.amountPaise(), settleRefund(opened, booking.id()));
    }

    /** Same shape as FR-24's compensation: intent committed, then the gateway. */
    private RefundSettlement settleRefund(PendingRefund pending, long bookingId) {
        var result =
                gateway.refund(
                        new PaymentGateway.RefundRequest(
                                pending.paymentReference(),
                                pending.amountPaise(),
                                RefundReason.CANCELLED.name()));

        var settlement =
                switch (result) {
                    case PaymentGateway.RefundOutcome.Accepted ignored ->
                            RefundSettlement.COMPLETED;
                    case PaymentGateway.RefundOutcome.Rejected ignored ->
                            RefundSettlement.FAILED;
                    // Unknown, not failed. Marking it failed would assert the money
                    // is still with the PSP when it may not be, and a retry would
                    // then refund twice.
                    case PaymentGateway.RefundOutcome.Unreachable ignored ->
                            RefundSettlement.PENDING;
                };

        unitOfWork.inTransaction(
                () -> {
                    switch (settlement) {
                        case COMPLETED ->
                                payments.completeRefund(
                                        pending.refundId(),
                                        pending.paymentId(),
                                        bookingId,
                                        pending.amountPaise());
                        case FAILED -> payments.failRefund(pending.refundId());
                        case PENDING, NOT_OWED -> {
                            // The row stays PENDING for a retry.
                        }
                    }
                    return null;
                });

        return settlement;
    }

    private record PendingRefund(
            long refundId, long paymentId, String paymentReference, long amountPaise) {}

    public enum RefundSettlement {
        COMPLETED,
        FAILED,
        /** Unknown; the {@code refunds} row stays {@code PENDING}. */
        PENDING,
        /** FR-45, or FR-44's zero tier. Nothing was owed, so nothing was attempted. */
        NOT_OWED
    }

    public sealed interface Outcome {

        /** A confirmed booking cancelled. {@code refundPaise} may legitimately be 0. */
        record Cancelled(long bookingId, long refundPaise, RefundSettlement settlement)
                implements Outcome {}

        /** An unpaid hold released (FR-43). No money was involved at any point. */
        record Released(long bookingId) implements Outcome {}

        /** Already expired, cancelled or reaped. A no-op, and the caller's goal. */
        record AlreadyResolved(long bookingId) implements Outcome {}

        /** A state with no cancellation path — {@code FAILED}, say (FR-27). */
        record NotCancellable(long bookingId, BookingStatus status) implements Outcome {}

        /** No such PNR, or not this caller's. The two are deliberately alike. */
        record UnknownBooking(String pnr) implements Outcome {}
    }
}
