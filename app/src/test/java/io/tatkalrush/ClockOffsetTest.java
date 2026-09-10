package io.tatkalrush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TatkalWindow;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-31's clock, as a runtime setting.
 *
 * <p>The seed's {@code BASE_DATE} is fixed at 2026-10-01 so two runs are
 * comparable (FR-50). That makes every TATKAL window in the dataset shut for
 * anyone running before late September 2026 — and AC-1.11 requires P1 to run
 * against an <b>open</b> one, so the profile that gives the project its name would
 * otherwise spend its entire spike collecting {@code QUOTA_LOCKED}.
 *
 * <p>The lever moves the system, not the data. Shifting {@code BASE_DATE} instead
 * would invalidate every benchmark in {@code docs/benchmarks/} for comparison
 * purposes, which is the one thing the fixed date exists to protect.
 */
class ClockOffsetTest {

    private final ApplicationWiring wiring = new ApplicationWiring();

    @Test
    void theDefaultIsTheSystemClock() {
        // Not "approximately the system clock". A benchmark that silently ran at an
        // offset would put a wrong instant into every row it wrote.
        assertSame(InstantSource.system(), wiring.clock("PT0S"));
    }

    @Test
    void anOffsetMovesNowByExactlyThatMuch() {
        InstantSource shifted = wiring.clock("P21D");

        long skewMillis =
                Duration.between(Instant.now(), shifted.instant()).toMillis();

        // 21 days, give or take the microseconds between the two reads.
        assertTrue(
                Math.abs(skewMillis - Duration.ofDays(21).toMillis()) < 5_000,
                "expected ~21 days of skew, got " + Duration.ofMillis(skewMillis));
    }

    @Test
    void aNegativeOffsetIsAllowed() {
        // Useful for the opposite case: putting the system BEFORE a window to
        // observe QUOTA_LOCKED under load, which is a legitimate thing to profile.
        assertTrue(wiring.clock("PT-48H").instant().isBefore(Instant.now()));
    }

    @Test
    @DisplayName("time still flows - this is an offset, not a freeze")
    void timeAdvances() throws InterruptedException {
        InstantSource shifted = wiring.clock("P21D");

        Instant first = shifted.instant();
        Thread.sleep(20);
        Instant second = shifted.instant();

        // A fixed instant would stop holds from expiring, so FR-18's reaper would
        // have nothing to sweep and FR-24's expiry check could never fire - two of
        // the behaviours a load run exists to exercise.
        assertNotEquals(first, second);
        assertTrue(second.isAfter(first));
    }

    @Test
    void aMalformedOffsetFailsAtStartupRatherThanAtTheFirstRequest() {
        // Duration.parse, so "21d" and "3 weeks" are rejected loudly while the
        // operator is still looking at the console.
        assertThrows(DateTimeParseException.class, () -> wiring.clock("21d"));
        assertThrows(DateTimeParseException.class, () -> wiring.clock("three weeks"));
    }

    @Test
    @DisplayName("AC-1.11: an offset opens the Tatkal window on the seeded dataset")
    void anOffsetOpensTheSeededDatasetsTatkalWindow() {
        // The seed's earliest journey date. Sleeper TATKAL opens at 11:00 IST on
        // D-1, which is 2026-09-30T05:30Z.
        LocalDate earliestJourney = LocalDate.of(2026, 10, 1);

        Instant opensAt = TatkalWindow.opensAt(earliestJourney, TravelClass.SL);
        Duration toTheWindow = Duration.between(Instant.now(), opensAt);

        // Whatever "now" is when this runs, an offset one hour past the window's
        // opening puts the system inside it. Computed rather than hard-coded: a
        // literal would rot the moment real time passed the seeded dates.
        InstantSource shifted = wiring.clock(toTheWindow.plusHours(1).toString());

        assertTrue(
                TatkalWindow.isPoolOpen(
                        QuotaType.TATKAL, earliestJourney, TravelClass.SL, shifted.instant()),
                "the window should be open at " + shifted.instant());
    }

    @Test
    void generalIsUnaffectedByTheOffsetEitherWay() {
        // FR-8: only TATKAL has a window. Asserted because an offset large enough
        // to open TATKAL is also large enough to hide a bug that gated GENERAL.
        assertTrue(
                TatkalWindow.isPoolOpen(
                        QuotaType.GENERAL,
                        LocalDate.of(2026, 10, 1),
                        TravelClass.SL,
                        wiring.clock("PT-8760H").instant()));
    }
}
