package io.tatkalrush.domain.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TravelClass;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Fare computation (FR-67, FR-68). */
class FareCalculatorTest {

    @Test
    @DisplayName("§6.9's worked example, executable")
    void sddWorkedExample() {
        // NDLS->RTM in 3A: segment 0 is 465 km, segment 1 is 265 km.
        //   distance = 730 km
        //   3A rate  = 285 paise/km, base 4,000 paise
        //   fare     = ceil(730 x 285) + 4000 = 208,050 + 4,000 = 212,050
        //
        // The SDD calls its rates illustrative; FareRates uses these exact
        // numbers so the example is executable rather than decorative. If the
        // table is ever retuned, this fails and the SDD gets updated with it.
        long fare =
                FareCalculator.perPassengerPaise(
                        new BigDecimal("730"), TravelClass.AC3, QuotaType.GENERAL);

        assertEquals(212_050L, fare, "§6.9's worked example must hold");
    }

    @Nested
    @DisplayName("FR-67: ceil(distance x rate) + base")
    class Formula {

        @Test
        @DisplayName("fractional distances round UP, never down")
        void fractionalDistancesRoundUp() {
            // distance_km is NUMERIC(7,2), so fractional paise are routine.
            // 730.25 x 285 = 208,121.25 -> ceil -> 208,122, plus 4,000 base.
            assertEquals(
                    212_122L,
                    FareCalculator.perPassengerPaise(
                            new BigDecimal("730.25"), TravelClass.AC3, QuotaType.GENERAL));

            // Exactly on the boundary: no rounding to do.
            assertEquals(
                    212_050L,
                    FareCalculator.perPassengerPaise(
                            new BigDecimal("730.00"), TravelClass.AC3, QuotaType.GENERAL));

            // One hundredth of a km still pushes it up a whole paise.
            assertEquals(
                    212_053L,
                    FareCalculator.perPassengerPaise(
                            new BigDecimal("730.01"), TravelClass.AC3, QuotaType.GENERAL));
        }

        @Test
        @DisplayName("a zero-distance journey still costs the base fare")
        void zeroDistanceCostsBase() {
            assertEquals(
                    4_000L,
                    FareCalculator.perPassengerPaise(
                            BigDecimal.ZERO, TravelClass.AC3, QuotaType.GENERAL));
        }

        @ParameterizedTest
        @CsvSource({
            // class, distance, expected GENERAL fare per passenger
            "SL,  730, 56250", // 730*75  + 1500
            "CC,  730, 133900", // 730*180 + 2500
            "AC3, 730, 212050", // 730*285 + 4000
            "AC2, 730, 312600", // 730*420 + 6000
            "AC1, 730, 535600", // 730*720 + 10000
        })
        @DisplayName("every class prices consistently with its table row")
        void allClassesPriceConsistently(TravelClass cls, String km, long expected) {
            assertEquals(
                    expected,
                    FareCalculator.perPassengerPaise(
                            new BigDecimal(km), cls, QuotaType.GENERAL));
        }

        @Test
        @DisplayName("FR-67: classes are ordered SL cheapest through 1A dearest")
        void classesAreOrderedByPrice() {
            var distance = new BigDecimal("500");
            long sl = FareCalculator.perPassengerPaise(distance, TravelClass.SL, QuotaType.GENERAL);
            long cc = FareCalculator.perPassengerPaise(distance, TravelClass.CC, QuotaType.GENERAL);
            long a3 = FareCalculator.perPassengerPaise(distance, TravelClass.AC3, QuotaType.GENERAL);
            long a2 = FareCalculator.perPassengerPaise(distance, TravelClass.AC2, QuotaType.GENERAL);
            long a1 = FareCalculator.perPassengerPaise(distance, TravelClass.AC1, QuotaType.GENERAL);

            assertTrue(sl < cc && cc < a3 && a3 < a2 && a2 < a1,
                    "FR-67 requires SL cheapest through 1A dearest, got "
                            + sl + " " + cc + " " + a3 + " " + a2 + " " + a1);
        }
    }

    @Nested
    @DisplayName("FR-68: the TATKAL surcharge")
    class TatkalSurcharge {

        @ParameterizedTest
        @EnumSource(TravelClass.class)
        @DisplayName("TATKAL always costs more than GENERAL, in every class")
        void tatkalCostsMore(TravelClass cls) {
            var distance = new BigDecimal("400");
            long general = FareCalculator.perPassengerPaise(distance, cls, QuotaType.GENERAL);
            long tatkal = FareCalculator.perPassengerPaise(distance, cls, QuotaType.TATKAL);

            // FR-45 forfeits the whole fare on a Tatkal cancellation. If the
            // surcharge were zero the rule would have nothing to bite on, which
            // is exactly what FR-68 exists to prevent.
            assertTrue(tatkal > general, cls + ": TATKAL must carry a surcharge");
            assertEquals(FareRates.forClass(cls).tatkalSurchargePaise(), tatkal - general);
        }

        @Test
        @DisplayName("the surcharge is per passenger, not per booking")
        void surchargeIsPerPassenger() {
            var distance = new BigDecimal("730");
            long forOne = FareCalculator.farePaise(distance, TravelClass.AC3, QuotaType.TATKAL, 1);
            long forFour = FareCalculator.farePaise(distance, TravelClass.AC3, QuotaType.TATKAL, 4);

            assertEquals(forOne * 4, forFour, "FR-68 says per passenger");
            assertEquals(242_050L, forOne, "212,050 fare + 30,000 surcharge");
        }
    }

    @Nested
    @DisplayName("group bookings and validation")
    class GroupsAndValidation {

        @Test
        @DisplayName("total fare scales exactly with passenger count")
        void faresScaleWithPassengers() {
            var distance = new BigDecimal("517.37");
            for (int n = 1; n <= 6; n++) {
                assertEquals(
                        FareCalculator.perPassengerPaise(distance, TravelClass.SL, QuotaType.GENERAL)
                                * n,
                        FareCalculator.farePaise(distance, TravelClass.SL, QuotaType.GENERAL, n),
                        n + " passengers");
            }
        }

        @Test
        @DisplayName("a negative distance is refused rather than priced")
        void negativeDistanceRefused() {
            // Only reachable if train_stops.distance_km is non-monotonic, which
            // the seed generator guarantees against and a test asserts. Failing
            // here beats emitting a negative fare that INV-7 would later report
            // as a pricing mismatch rather than as bad data.
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            FareCalculator.farePaise(
                                    new BigDecimal("-1"), TravelClass.SL, QuotaType.GENERAL, 1));
        }

        @Test
        @DisplayName("a zero-passenger booking is refused")
        void zeroPassengersRefused() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            FareCalculator.farePaise(
                                    BigDecimal.TEN, TravelClass.SL, QuotaType.GENERAL, 0));
        }

        @Test
        @DisplayName("FR-67b: the same inputs always give the same answer")
        void isDeterministic() {
            // INV-7 recomputes independently and compares for exact equality. Any
            // non-determinism here - a clock read, a locale, a hash order - would
            // make that comparison fail at random and look like a pricing bug.
            var distance = new BigDecimal("1234.56");
            long first = FareCalculator.farePaise(distance, TravelClass.AC2, QuotaType.TATKAL, 3);
            for (int i = 0; i < 100; i++) {
                assertEquals(
                        first,
                        FareCalculator.farePaise(distance, TravelClass.AC2, QuotaType.TATKAL, 3));
            }
        }
    }
}
