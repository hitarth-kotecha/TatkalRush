package io.tatkalrush.domain.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-62's entire personal-data surface.
 *
 * <p>The validation here duplicates the {@code passengers} table's CHECK
 * constraints on purpose. The database is the authority; this is the layer that
 * can say <em>which</em> passenger was wrong, where a constraint failure can only
 * name the table.
 */
class PassengerTest {

    @Test
    @DisplayName("a well-formed passenger is accepted")
    void accepted() {
        var p = new Passenger("Asha Menon", 34, Passenger.Gender.F);

        assertEquals("Asha Menon", p.name());
        assertEquals(34, p.age());
    }

    @Test
    @DisplayName("the age bounds match the column's CHECK (0..120)")
    void ageBounds() {
        new Passenger("Infant", 0, Passenger.Gender.O);
        new Passenger("Elder", 120, Passenger.Gender.O);

        assertThrows(
                IllegalArgumentException.class,
                () -> new Passenger("Impossible", 121, Passenger.Gender.O));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Passenger("Impossible", -1, Passenger.Gender.O));
    }

    @Test
    @DisplayName("the failure names the offending value, not just the rule")
    void messageCarriesTheValue() {
        var thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new Passenger("Too old", 200, Passenger.Gender.M));

        // A booking may carry six passengers. "age must be 0..120" alone would
        // leave the caller to work out which of the six was rejected.
        assertTrue(thrown.getMessage().contains("200"), thrown.getMessage());
    }

    @Test
    @DisplayName("a blank name is refused, because the column is NOT NULL")
    void nameRequired() {
        assertThrows(
                IllegalArgumentException.class, () -> new Passenger("  ", 30, Passenger.Gender.O));
        assertThrows(
                IllegalArgumentException.class, () -> new Passenger(null, 30, Passenger.Gender.O));
    }

    @Test
    @DisplayName("gender is a closed set, matching the column's CHECK")
    void genderIsClosed() {
        assertEquals(3, Passenger.Gender.values().length);
        assertThrows(IllegalArgumentException.class, () -> new Passenger("Nobody", 30, null));
    }
}
