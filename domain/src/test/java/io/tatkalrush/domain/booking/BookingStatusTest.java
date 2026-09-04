package io.tatkalrush.domain.booking;

import static io.tatkalrush.domain.booking.BookingStatus.CANCELLED;
import static io.tatkalrush.domain.booking.BookingStatus.CONFIRMED;
import static io.tatkalrush.domain.booking.BookingStatus.EXPIRED;
import static io.tatkalrush.domain.booking.BookingStatus.FAILED;
import static io.tatkalrush.domain.booking.BookingStatus.FAILED_REFUNDED;
import static io.tatkalrush.domain.booking.BookingStatus.HELD;
import static io.tatkalrush.domain.booking.BookingStatus.PAYMENT_PENDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The booking state machine (FR-27).
 *
 * <p>§6.4 calls the diagram "the single source of truth for the Reviewer agent"
 * and says any transition not on it is a defect. This transcribes the diagram
 * edge by edge, so the code and the SDD can be compared by reading them side by
 * side.
 */
class BookingStatusTest {

    @Nested
    @DisplayName("the edges on the diagram")
    class LegalEdges {

        @ParameterizedTest
        @CsvSource({
            "HELD,            PAYMENT_PENDING", // initiate payment
            "HELD,            EXPIRED", // hold lapses / user releases
            "PAYMENT_PENDING, CONFIRMED", // PSP success, hold live, rows inserted
            "PAYMENT_PENDING, FAILED", // PSP failure
            "PAYMENT_PENDING, FAILED_REFUNDED", // hold expired OR allocation conflict
            "CONFIRMED,       CANCELLED", // user cancels
        })
        @DisplayName("every edge in §6.4 is permitted")
        void diagramEdgesAreAllowed(BookingStatus from, BookingStatus to) {
            assertTrue(from.canTransitionTo(to), from + " -> " + to + " is on the diagram");
            from.requireTransitionTo(to); // must not throw
        }

        @Test
        @DisplayName("the transition table matches the diagram exactly")
        void transitionTableIsComplete() {
            // Asserted as whole sets rather than edge by edge, so an edge ADDED
            // by mistake fails too. Checking only the edges that should exist
            // would let a stray one through unnoticed.
            assertEquals(Set.of(PAYMENT_PENDING, EXPIRED), HELD.allowedTargets());
            assertEquals(
                    Set.of(CONFIRMED, FAILED, FAILED_REFUNDED),
                    PAYMENT_PENDING.allowedTargets());
            assertEquals(Set.of(CANCELLED), CONFIRMED.allowedTargets());
            assertEquals(Set.of(), CANCELLED.allowedTargets());
            assertEquals(Set.of(), EXPIRED.allowedTargets());
            assertEquals(Set.of(), FAILED.allowedTargets());
            assertEquals(Set.of(), FAILED_REFUNDED.allowedTargets());
        }
    }

    @Nested
    @DisplayName("the edge that must NOT exist")
    class ForbiddenEdges {

        @Test
        @DisplayName("FR-43: there is no HELD -> CANCELLED")
        void noHeldToCancelled() {
            // Not pedantry. CANCELLED means a confirmed booking that was paid
            // for, and it triggers FR-44's refund tiers. A held booking has taken
            // no money, so routing it there would compute a refund against a
            // payment that never happened. Cancelling an unpaid hold is a
            // RELEASE and lands on EXPIRED.
            assertFalse(HELD.canTransitionTo(CANCELLED));

            var error = assertThrows(IllegalStateException.class,
                    () -> HELD.requireTransitionTo(CANCELLED));
            assertTrue(error.getMessage().contains("FR-27"), error.getMessage());
            assertTrue(
                    error.getMessage().contains("EXPIRED"),
                    () -> "the message should point at the legal alternatives: "
                            + error.getMessage());
        }

        @Test
        @DisplayName("a hold cannot be confirmed without going through payment")
        void noHeldToConfirmed() {
            assertFalse(HELD.canTransitionTo(CONFIRMED));
        }

        @Test
        @DisplayName("terminal states are terminal")
        void terminalStatesAreTerminal() {
            for (BookingStatus terminal :
                    new BookingStatus[] {CANCELLED, EXPIRED, FAILED, FAILED_REFUNDED}) {
                assertTrue(terminal.isTerminal(), terminal + " must be terminal");
                for (BookingStatus any : BookingStatus.values()) {
                    assertFalse(
                            terminal.canTransitionTo(any),
                            terminal + " -> " + any + " must not exist");
                }
            }
        }

        @Test
        @DisplayName("an expired hold cannot be revived")
        void expiredIsFinal() {
            // Once the berths are back in the pool they may already belong to
            // someone else. Reviving the booking would be an overbooking the
            // allocator never saw.
            assertFalse(EXPIRED.canTransitionTo(HELD));
            assertFalse(EXPIRED.canTransitionTo(CONFIRMED));
        }

        @Test
        @DisplayName("no state transitions to itself")
        void noSelfTransitions() {
            for (BookingStatus status : BookingStatus.values()) {
                assertFalse(
                        status.canTransitionTo(status),
                        status + " -> " + status + " would make a retry look like progress");
            }
        }
    }

    @Nested
    @DisplayName("completeness")
    class Completeness {

        @ParameterizedTest
        @EnumSource(BookingStatus.class)
        @DisplayName("every state has a decided transition set")
        void everyStateIsDecided(BookingStatus status) {
            // Guards against adding a state to the enum and forgetting to decide
            // its edges: the table lists terminal states explicitly rather than
            // defaulting, so a new state without an entry throws here rather than
            // silently becoming terminal.
            assertEquals(
                    status.allowedTargets(),
                    status.allowedTargets(),
                    "allowedTargets must not throw for " + status);
        }

        @ParameterizedTest
        @CsvSource({
            "CONFIRMED,       true",
            "CANCELLED,       true",
            "FAILED_REFUNDED, true",
            "HELD,            false",
            "PAYMENT_PENDING, false",
            "EXPIRED,         false",
            "FAILED,          false",
        })
        @DisplayName("money-captured states are exactly the ones that took payment")
        void moneyCapturedIsCorrect(BookingStatus status, boolean captured) {
            // Drives INV-3 (no orphaned payments) and the refund paths. FAILED
            // took nothing, so it is refundless; FAILED_REFUNDED captured and
            // returned it, which is a different thing entirely.
            assertEquals(captured, status.moneyWasCaptured(), status.toString());
        }

        @Test
        @DisplayName("both routes into FAILED_REFUNDED exist and share the state")
        void bothFailedRefundedRoutes() {
            // FR-24's benign expiry race and DD-008's allocation conflict share a
            // terminal state and differ entirely in meaning. One is expected under
            // chaos C2 and C5; the other fails the run under INV-11 and NFR-9.
            // They are told apart by refunds.reason, never by the booking state -
            // which is exactly why that column exists.
            assertTrue(PAYMENT_PENDING.canTransitionTo(FAILED_REFUNDED));
            assertTrue(FAILED_REFUNDED.moneyWasCaptured());
        }
    }
}
