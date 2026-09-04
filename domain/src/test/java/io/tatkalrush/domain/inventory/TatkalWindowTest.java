package io.tatkalrush.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The Tatkal window (FR-28 to FR-31, AC-1.11). */
class TatkalWindowTest {

    private static final LocalDate JOURNEY = LocalDate.of(2026, 10, 2);

    /** 10:00 IST on 1 October 2026 is 04:30 UTC. */
    private static final Instant AC_OPENS = Instant.parse("2026-10-01T04:30:00Z");

    /** 11:00 IST on 1 October 2026 is 05:30 UTC. */
    private static final Instant SL_OPENS = Instant.parse("2026-10-01T05:30:00Z");

    @Nested
    @DisplayName("FR-28: 10:00 IST on D-1 for AC, 11:00 for sleeper")
    class OpeningInstant {

        @ParameterizedTest
        @EnumSource(
                value = TravelClass.class,
                names = {"AC1", "AC2", "AC3", "CC"})
        @DisplayName("AC classes open at 10:00 IST the day before")
        void acClassesOpenAtTen(TravelClass travelClass) {
            assertEquals(AC_OPENS, TatkalWindow.opensAt(JOURNEY, travelClass));
        }

        @Test
        @DisplayName("sleeper opens an hour later, at 11:00 IST")
        void sleeperOpensAtEleven() {
            assertEquals(SL_OPENS, TatkalWindow.opensAt(JOURNEY, TravelClass.SL));
            assertEquals(
                    Duration.ofHours(1),
                    Duration.between(
                            TatkalWindow.opensAt(JOURNEY, TravelClass.AC3),
                            TatkalWindow.opensAt(JOURNEY, TravelClass.SL)),
                    "FR-28 puts sleeper exactly one hour after AC");
        }

        @Test
        @DisplayName("D-1 is the previous calendar day, not 24 hours before departure")
        void dMinusOneIsACalendarDay() {
            // The distinction that would be easy to get wrong. A train leaving
            // 2 October opens on 1 October at 10:00 IST whether it departs at
            // 06:00 or 23:55 - so the gap to departure varies, and computing it
            // as a fixed offset from departure would be wrong for every train
            // that is not a 10:00 departure.
            assertEquals(
                    Instant.parse("2026-10-01T04:30:00Z"),
                    TatkalWindow.opensAt(LocalDate.of(2026, 10, 2), TravelClass.AC3));
            assertEquals(
                    Instant.parse("2026-10-02T04:30:00Z"),
                    TatkalWindow.opensAt(LocalDate.of(2026, 10, 3), TravelClass.AC3));
        }

        @Test
        @DisplayName("month and year boundaries are handled by the date arithmetic")
        void crossesMonthAndYearBoundaries() {
            assertEquals(
                    Instant.parse("2026-09-30T04:30:00Z"),
                    TatkalWindow.opensAt(LocalDate.of(2026, 10, 1), TravelClass.AC3));
            assertEquals(
                    Instant.parse("2026-12-31T04:30:00Z"),
                    TatkalWindow.opensAt(LocalDate.of(2027, 1, 1), TravelClass.AC3));
            // A leap day, because February is where date arithmetic goes wrong.
            assertEquals(
                    Instant.parse("2028-02-29T04:30:00Z"),
                    TatkalWindow.opensAt(LocalDate.of(2028, 3, 1), TravelClass.AC3));
        }
    }

    @Nested
    @DisplayName("FR-29/FR-30: open is decided per request, from the clock")
    class Openness {

        @Test
        @DisplayName("closed a millisecond before, open on the instant")
        void boundaryIsInclusive() {
            // The boundary must fall somewhere. Opening ON the instant is what a
            // user reading "opens at 10:00" expects, and it means no request is
            // ever refused at exactly the advertised time.
            assertFalse(
                    TatkalWindow.isOpen(JOURNEY, TravelClass.AC3, AC_OPENS.minusMillis(1)),
                    "one millisecond early is closed");
            assertTrue(
                    TatkalWindow.isOpen(JOURNEY, TravelClass.AC3, AC_OPENS),
                    "exactly on the instant is open");
            assertTrue(TatkalWindow.isOpen(JOURNEY, TravelClass.AC3, AC_OPENS.plusMillis(1)));
        }

        @Test
        @DisplayName("sleeper is still closed when AC has already opened")
        void classesOpenIndependently() {
            // Between 10:00 and 11:00 IST the answer differs by class. A single
            // per-schedule flag could not represent this, which is one more
            // reason the answer is computed rather than stored.
            Instant between = AC_OPENS.plus(Duration.ofMinutes(30));

            assertTrue(TatkalWindow.isOpen(JOURNEY, TravelClass.AC3, between));
            assertFalse(TatkalWindow.isOpen(JOURNEY, TravelClass.SL, between));
        }

        @Test
        @DisplayName("it stays open once opened")
        void staysOpen() {
            assertTrue(
                    TatkalWindow.isOpen(JOURNEY, TravelClass.AC3, AC_OPENS.plus(Duration.ofDays(1))),
                    "the window opens and does not close again before departure");
        }

        @Test
        @DisplayName("FR-30: the same inputs always give the same answer")
        void isAPureFunction() {
            // The property a scheduled job cannot have. Every replica, on every
            // request, computes the same answer from the same clock - so there is
            // no instant at which "the job has not run yet" and no herd waiting
            // for it to fire.
            for (int i = 0; i < 1_000; i++) {
                assertTrue(TatkalWindow.isOpen(JOURNEY, TravelClass.AC3, AC_OPENS));
                assertFalse(
                        TatkalWindow.isOpen(JOURNEY, TravelClass.AC3, AC_OPENS.minusSeconds(1)));
            }
        }
    }

    @Nested
    @DisplayName("pool gating")
    class PoolGating {

        @ParameterizedTest
        @EnumSource(TravelClass.class)
        @DisplayName("GENERAL is always open, in every class and at any time")
        void generalIsAlwaysOpen(TravelClass travelClass) {
            // FR-8. Answering for both quotas in one place keeps a caller from
            // having to remember which are gated - the kind of thing that gets
            // forgotten in one code path out of three.
            Instant longBefore = AC_OPENS.minus(Duration.ofDays(30));

            assertTrue(
                    TatkalWindow.isPoolOpen(
                            QuotaType.GENERAL, JOURNEY, travelClass, longBefore));
        }

        @ParameterizedTest
        @CsvSource({"AC3, false", "SL, false"})
        @DisplayName("TATKAL is gated before its instant")
        void tatkalIsGated(TravelClass travelClass, boolean expected) {
            assertEquals(
                    expected,
                    TatkalWindow.isPoolOpen(
                            QuotaType.TATKAL, JOURNEY, travelClass, AC_OPENS.minusSeconds(1)));
        }

        @Test
        @DisplayName("AC-1.11: a load test can open the window without waiting for 10 AM")
        void windowIsTestableWithoutWaiting() {
            // FR-31 calls the injected Clock mandatory: "a test suite that cannot
            // control time cannot test this system". This is that claim, asserted -
            // P1 runs against an open Tatkal window by choosing `now`, not by
            // running at 04:30 UTC.
            Instant simulatedNow = TatkalWindow.opensAt(JOURNEY, TravelClass.AC3);

            assertTrue(
                    TatkalWindow.isPoolOpen(QuotaType.TATKAL, JOURNEY, TravelClass.AC3, simulatedNow),
                    "P1 must be able to run inside the window at any wall-clock time");
        }
    }
}
