package io.tatkalrush.domain.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.domain.inventory.QuotaType;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Refund rules (FR-44, FR-45, FR-46). */
class RefundPolicyTest {

    private static final Instant DEPARTURE = Instant.parse("2026-10-01T16:55:00Z");
    private static final long FARE = 212_050L;

    private static Instant hoursBefore(double hours) {
        return DEPARTURE.minus(Duration.ofMinutes((long) (hours * 60)));
    }

    @Nested
    @DisplayName("FR-44: three tiers by time before departure")
    class Tiers {

        @ParameterizedTest
        @CsvSource({
            "72.0, EARLY", // well clear
            "48.5, EARLY",
            "48.0, STANDARD", // exactly 48 h is NOT "more than 48 h"
            "24.0, STANDARD",
            "12.0, STANDARD", // exactly 12 h is still within the 12-48 band
            "11.9, LATE",
            "1.0,  LATE",
            "0.0,  LATE", // at departure
        })
        @DisplayName("boundaries land where FR-44 says")
        void tierBoundaries(double hoursBefore, RefundPolicy.Tier expected) {
            // The boundaries are the whole content of FR-44, and "> 48 h" versus
            // ">= 48 h" is a real 40-percentage-point difference to a passenger
            // cancelling exactly two days out.
            assertEquals(expected, RefundPolicy.tierFor(hoursBefore(hoursBefore), DEPARTURE));
        }

        @ParameterizedTest
        @CsvSource({
            "72.0, 190845", // 90% of 212,050
            "24.0, 106025", // 50%
            "6.0,  0", // nothing
        })
        @DisplayName("GENERAL bookings refund by tier")
        void generalRefunds(double hoursBefore, long expected) {
            assertEquals(
                    expected,
                    RefundPolicy.refundOnCancellation(
                            FARE, QuotaType.GENERAL, hoursBefore(hoursBefore), DEPARTURE));
        }

        @Test
        @DisplayName("cancelling after departure refunds nothing, and does not throw")
        void cancellationAfterDeparture() {
            // Clocks skew, webhooks arrive late, and chaos runs reorder events on
            // purpose. Throwing here would turn an awkward-but-real case into a
            // failed request; LATE is the correct and safe answer.
            long refund =
                    RefundPolicy.refundOnCancellation(
                            FARE, QuotaType.GENERAL, DEPARTURE.plusSeconds(3600), DEPARTURE);
            assertEquals(0, refund);
            assertEquals(
                    RefundPolicy.Tier.LATE,
                    RefundPolicy.tierFor(DEPARTURE.plusSeconds(3600), DEPARTURE));
        }

        @Test
        @DisplayName("rounding is down, and deterministic")
        void roundsDown() {
            // 90% of 1,001 is 900.9. INV-7 recomputes the retained amount with
            // this same expression, so what matters most is that both sides round
            // identically - the direction is secondary.
            assertEquals(
                    900,
                    RefundPolicy.refundOnCancellation(
                            1_001, QuotaType.GENERAL, hoursBefore(72), DEPARTURE));
        }
    }

    @Nested
    @DisplayName("FR-45: confirmed TATKAL bookings are never refunded")
    class TatkalNeverRefunded {

        @ParameterizedTest
        @CsvSource({"720.0", "72.0", "48.5", "24.0", "1.0"})
        @DisplayName("no refund at any point before departure")
        void tatkalRefundsNothingEver(double hoursBefore) {
            // Checked BEFORE the tier, not after. At 72 hours out the EARLY tier
            // would otherwise return 90% of a fare that is never refundable -
            // and the bug would only show for early cancellations, which are the
            // least likely to be noticed.
            assertEquals(
                    0,
                    RefundPolicy.refundOnCancellation(
                            FARE, QuotaType.TATKAL, hoursBefore(hoursBefore), DEPARTURE),
                    "FR-45 is absolute, regardless of window");
        }

        @Test
        @DisplayName("the forfeited amount includes FR-68's surcharge")
        void tatkalForfeitsTheSurchargeToo() {
            // FR-68 and FR-45 are a pair: the surcharge makes Tatkal dearer, and
            // FR-45 means you lose all of it. Either alone is uninteresting.
            long tatkalFare = FARE + 30_000;
            assertEquals(
                    0,
                    RefundPolicy.refundOnCancellation(
                            tatkalFare, QuotaType.TATKAL, hoursBefore(100), DEPARTURE));
            assertTrue(
                    RefundPolicy.refundOnCancellation(
                                    tatkalFare, QuotaType.GENERAL, hoursBefore(100), DEPARTURE)
                            > 0,
                    "the same fare under GENERAL would refund - the difference is FR-45");
        }
    }

    @Nested
    @DisplayName("FR-46: chart-time waitlist refunds")
    class ChartWaitlist {

        @Test
        @DisplayName("a waitlisted booking is refunded in full, overriding FR-44")
        void fullRefundAtChart() {
            // Hours before departure, FR-44's LATE tier would return nothing. But
            // this is the railway failing to seat a passenger rather than a
            // passenger changing their mind, so the full fare comes back.
            assertEquals(FARE, RefundPolicy.refundOnChartWaitlist(FARE));

            assertEquals(
                    0,
                    RefundPolicy.refundOnCancellation(
                            FARE, QuotaType.GENERAL, hoursBefore(4), DEPARTURE),
                    "the same instant under FR-44 would refund nothing");
        }

        @Test
        @DisplayName("even a TATKAL waitlist is refunded in full")
        void tatkalWaitlistIsRefunded() {
            // FR-45 forfeits a CONFIRMED Tatkal booking. A waitlisted one was
            // never seated, so FR-46 applies and there is nothing to forfeit.
            // These two rules meeting is the subtlest case in the refund logic,
            // and separate methods are what keep them from colliding.
            assertEquals(242_050L, RefundPolicy.refundOnChartWaitlist(242_050L));
        }
    }
}
