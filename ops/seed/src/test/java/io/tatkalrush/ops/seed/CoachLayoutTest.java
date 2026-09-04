package io.tatkalrush.ops.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pure-logic checks on the coach layouts. No database, no container.
 *
 * <p>These matter because FR-38 defines the RAC allowance as
 * {@code 2 x side_lower_berth_count}. If a layout is wrong, waitlist capacity is
 * wrong, and P5 measures promotion behaviour against a number that describes no
 * real train.
 */
class CoachLayoutTest {

    @ParameterizedTest
    @EnumSource(CoachLayout.class)
    @DisplayName("berthTypes() emits exactly berthCount() entries, all valid")
    void layoutIsInternallyConsistent(CoachLayout layout) {
        var types = layout.berthTypes();

        assertEquals(layout.berthCount(), types.size(), layout + " size mismatch");

        var valid = Set.of("LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER");
        for (String type : types) {
            assertTrue(valid.contains(type), layout + " produced invalid berth type: " + type);
        }
    }

    @Test
    @DisplayName("layouts match real Indian Railways coach composition")
    void layoutsMatchRealCoaches() {
        assertEquals(72, CoachLayout.SL.berthCount(), "sleeper: 9 bays of 8");
        assertEquals(64, CoachLayout.THREE_A.berthCount(), "3A: 8 bays of 8");
        assertEquals(48, CoachLayout.TWO_A.berthCount(), "2A: 8 bays of 6, no middle berth");
        assertEquals(24, CoachLayout.ONE_A.berthCount(), "1A: 6 cabins of 4");
    }

    @Test
    @DisplayName("FR-38: RAC allowance follows from side-lower count, and 1A has none")
    void racAllowanceFollowsFromSideLowerBerths() {
        assertEquals(9, CoachLayout.SL.sideLowerCount());
        assertEquals(18, CoachLayout.SL.racAllowance());

        assertEquals(8, CoachLayout.THREE_A.sideLowerCount());
        assertEquals(16, CoachLayout.THREE_A.racAllowance());

        // 1A has cabins, not open bays, so no side berths exist — and therefore
        // no RAC quota, exactly as on the real railway. If this ever becomes
        // non-zero, FR-38 is being computed from something other than the
        // physical layout.
        assertEquals(0, CoachLayout.ONE_A.sideLowerCount());
        assertEquals(0, CoachLayout.ONE_A.racAllowance());
    }

    @ParameterizedTest
    @EnumSource(CoachLayout.class)
    @DisplayName("berth type order is stable across calls - allocation depends on it")
    void berthTypeOrderIsStable(CoachLayout layout) {
        // Appendix A walks berths "ordered by ordinal", so this sequence is part
        // of the algorithm's observable behaviour. T-7 asserts the Java and Lua
        // implementations choose the SAME berth, not merely an equally valid one.
        assertEquals(layout.berthTypes(), layout.berthTypes());
    }

    @ParameterizedTest
    @EnumSource(CoachLayout.class)
    @DisplayName("every class maps to a travel_class the schema's CHECK accepts")
    void travelClassMatchesSchema(CoachLayout layout) {
        assertTrue(
                Set.of("SL", "3A", "2A", "1A", "CC").contains(layout.travelClass().code()),
                layout + " maps to " + layout.travelClass().code() + ", which V2's CHECK would reject");
    }
}
