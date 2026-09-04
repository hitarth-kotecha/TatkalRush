package io.tatkalrush.domain.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.tatkalrush.domain.PropertyRunner;
import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PNR generation and validation (FR-26, INV-6). */
class PnrTest {

    @Nested
    @DisplayName("FR-26: ten digits from a sequence, plus a Luhn check digit")
    class Generation {

        @Test
        @DisplayName("a PNR is ten digits and carries its sequence")
        void shapeAndSequence() {
            var pnr = Pnr.fromSequence(1);

            assertEquals(10, pnr.value().length());
            assertTrue(pnr.value().matches("\\d{10}"));
            assertEquals("000000001", pnr.value().substring(0, 9));
            assertEquals(1, pnr.sequence());
        }

        @ParameterizedTest
        @ValueSource(longs = {1, 2, 42, 12345, 999_999_998, 999_999_999})
        @DisplayName("every generated PNR validates")
        void generatedPnrsValidate(long sequence) {
            var pnr = Pnr.fromSequence(sequence);

            assertTrue(Pnr.isValid(pnr.value()), pnr + " should validate");
            assertEquals(sequence, pnr.sequence());
        }

        @Test
        @DisplayName("distinct sequences give distinct PNRs - no collisions to retry")
        void sequencesAreCollisionFree() {
            // The point of FR-26. Random generation with collision retry degrades
            // exactly under the load this project is about: as the space fills,
            // collisions rise, and the retry storm peaks when the system is
            // already at its limit. A sequence cannot collide by construction.
            var seen = new HashSet<String>();
            for (long seq = 1; seq <= 20_000; seq++) {
                assertTrue(
                        seen.add(Pnr.fromSequence(seq).value()),
                        "sequence " + seq + " produced a duplicate PNR");
            }
            assertEquals(20_000, seen.size());
        }

        @Test
        @DisplayName("a sequence beyond the 9-digit body is refused, not wrapped")
        void sequenceOverflowIsRefused() {
            // Wrapping would reissue a PNR that already belongs to another
            // booking, and the unique index would then fail a confirmation for a
            // customer whose money is already captured. Far better to fail at the
            // source with a clear message.
            assertThrows(IllegalArgumentException.class, () -> Pnr.fromSequence(1_000_000_000L));
            assertThrows(IllegalArgumentException.class, () -> Pnr.fromSequence(0));
            assertThrows(IllegalArgumentException.class, () -> Pnr.fromSequence(-1));
        }
    }

    @Nested
    @DisplayName("INV-6: the check digit does its job")
    class CheckDigit {

        @Test
        @DisplayName("a single-digit typo is caught")
        void catchesSingleDigitTypos() {
            // What the check digit is actually for: a human reading a PNR aloud.
            var pnr = Pnr.fromSequence(123_456_789L).value();

            int caught = 0;
            int total = 0;
            for (int position = 0; position < 10; position++) {
                for (char digit = '0'; digit <= '9'; digit++) {
                    if (pnr.charAt(position) == digit) {
                        continue;
                    }
                    var typo =
                            pnr.substring(0, position) + digit + pnr.substring(position + 1);
                    total++;
                    if (!Pnr.isValid(typo)) {
                        caught++;
                    }
                }
            }
            // Luhn catches ALL single-digit substitutions. Anything less means
            // the implementation is wrong, not that the algorithm is weak.
            assertEquals(total, caught, "Luhn must catch every single-digit substitution");
        }

        @Test
        @DisplayName("most adjacent transpositions are caught")
        void catchesAdjacentTranspositions() {
            var pnr = Pnr.fromSequence(918_273_645L).value();

            int caught = 0;
            int applicable = 0;
            for (int i = 0; i < 9; i++) {
                char a = pnr.charAt(i);
                char b = pnr.charAt(i + 1);
                if (a == b) {
                    continue; // swapping equal digits changes nothing
                }
                applicable++;
                var swapped =
                        pnr.substring(0, i) + b + a + pnr.substring(i + 2);
                if (!Pnr.isValid(swapped)) {
                    caught++;
                }
            }

            // Luhn misses exactly one transposition case: 09 <-> 90. So "most",
            // not "all" - and stating the limit honestly beats asserting a
            // stronger claim that would fail on the wrong input.
            assertTrue(
                    caught >= applicable - 1,
                    "expected at least " + (applicable - 1) + " of " + applicable
                            + " transpositions caught, got " + caught);
        }

        @Test
        @DisplayName("Luhn's known blind spot is demonstrated, not merely described")
        void knownBlindSpot() {
            // 09 <-> 90 is the one adjacent transposition Luhn cannot see:
            // doubling 0 gives 0 while doubling 9 gives 9 after the -9 fold, so
            // the pair contributes 9 either way round.
            //
            // Asserting the arithmetic would only restate the explanation. This
            // finds a real PNR whose 09/90 swap still validates, which is the
            // claim that actually matters - and if a future change to the
            // algorithm closed the gap, this test would fail and say so.
            String undetected = null;

            for (long sequence = 1; sequence <= 200_000 && undetected == null; sequence++) {
                String pnr = Pnr.fromSequence(sequence).value();
                for (int i = 0; i < 9; i++) {
                    char a = pnr.charAt(i);
                    char b = pnr.charAt(i + 1);
                    boolean zeroNine = (a == '0' && b == '9') || (a == '9' && b == '0');
                    if (!zeroNine) {
                        continue;
                    }
                    String swapped = pnr.substring(0, i) + b + a + pnr.substring(i + 2);
                    if (Pnr.isValid(swapped)) {
                        undetected = pnr + " -> " + swapped;
                        break;
                    }
                }
            }

            assertTrue(
                    undetected != null,
                    "expected to find a 09/90 transposition Luhn misses; if this now fails,"
                            + " the checksum has changed and the limit should be re-documented");
            System.out.println("[INV-6] Luhn blind spot, as expected: " + undetected);
        }

        @Test
        @DisplayName("a PNR with a wrong check digit is refused at construction")
        void wrongCheckDigitRefused() {
            var valid = Pnr.fromSequence(555).value();
            char wrong = valid.charAt(9) == '0' ? '1' : '0';
            var corrupted = valid.substring(0, 9) + wrong;

            assertFalse(Pnr.isValid(corrupted));
            assertThrows(IllegalArgumentException.class, () -> new Pnr(corrupted));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "123", "12345678901", "abcdefghij", "12345 6789"})
        @DisplayName("malformed input is refused")
        void malformedRefused(String candidate) {
            assertFalse(Pnr.isValid(candidate));
            assertThrows(IllegalArgumentException.class, () -> new Pnr(candidate));
        }

        @Test
        @DisplayName("null is refused rather than accepted as absent")
        void nullRefused() {
            assertFalse(Pnr.isValid(null));
            assertThrows(IllegalArgumentException.class, () -> new Pnr(null));
        }
    }

    @Nested
    @DisplayName("properties")
    class Properties {

        @Test
        @DisplayName("INV-6: every PNR from a valid sequence round-trips and validates")
        void generationRoundTrips() {
            PropertyRunner.check(
                    "fromSequence -> isValid -> sequence() returns the original",
                    20261001L,
                    2_000,
                    r -> 1L + r.nextInt(999_999_998),
                    sequence -> {
                        var pnr = Pnr.fromSequence(sequence);
                        assertTrue(Pnr.isValid(pnr.value()), () -> pnr + " failed validation");
                        assertEquals(sequence, pnr.sequence());
                        assertEquals(pnr, new Pnr(pnr.value()));
                    });
        }

        @Test
        @DisplayName("adjacent sequences never produce the same PNR")
        void adjacentSequencesDiffer() {
            PropertyRunner.check(
                    "consecutive sequence values give different PNRs",
                    20261002L,
                    2_000,
                    r -> 1L + r.nextInt(999_999_997),
                    sequence ->
                            assertNotEquals(
                                    Pnr.fromSequence(sequence), Pnr.fromSequence(sequence + 1)));
        }
    }
}
