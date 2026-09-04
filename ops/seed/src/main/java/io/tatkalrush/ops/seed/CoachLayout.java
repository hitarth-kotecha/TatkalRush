package io.tatkalrush.ops.seed;

import io.tatkalrush.domain.inventory.TravelClass;
import java.util.ArrayList;
import java.util.List;

/**
 * Berth composition of one coach, per travel class (FR-48).
 *
 * <p>This is not decoration. FR-38 defines the RAC allowance as
 * {@code 2 x side_lower_berth_count}, so the layout chosen here literally
 * determines how deep the RAC queue can go for each class — and DD-011's
 * waitlist behaviour is measured against it under P5. A distribution invented at
 * random would make FR-38 a number with no meaning behind it.
 *
 * <p>Layouts follow real Indian Railways coach composition, so the ratios are
 * defensible under questioning rather than merely plausible.
 *
 * <p>Chair car (CC) is deliberately absent. Its inventory is seats, not berths,
 * and it has no LOWER/UPPER/SIDE structure to map onto {@code berths.berth_type}.
 * Modelling it would mean either abusing the enum or widening the domain for a
 * class that adds nothing to the contention problem. Four classes still satisfies
 * FR-48's "3-5 classes".
 */
public enum CoachLayout {

    /**
     * Sleeper. Nine bays of eight: six main berths (2 lower, 2 middle, 2 upper)
     * plus a side lower and side upper.
     */
    SL(9, bay(2, 2, 2, 1, 1)),

    /** 3-tier AC. Eight bays, otherwise identical in structure to sleeper. */
    THREE_A(8, bay(2, 2, 2, 1, 1)),

    /** 2-tier AC. Eight bays of six: no middle berths. */
    TWO_A(8, bay(2, 0, 2, 1, 1)),

    /**
     * 1st AC. Six cabins of four, all lower and upper, and <b>no side berths at
     * all</b> — which is why 1A carries no RAC quota under FR-38, exactly as on
     * the real railway.
     */
    ONE_A(6, bay(2, 0, 2, 0, 0));

    private final int bays;
    private final List<String> bayPattern;

    CoachLayout(int bays, List<String> bayPattern) {
        this.bays = bays;
        this.bayPattern = bayPattern;
    }

    private static List<String> bay(int lower, int middle, int upper, int sideLower, int sideUpper) {
        var pattern = new ArrayList<String>();
        for (int i = 0; i < lower; i++) pattern.add("LOWER");
        for (int i = 0; i < middle; i++) pattern.add("MIDDLE");
        for (int i = 0; i < upper; i++) pattern.add("UPPER");
        for (int i = 0; i < sideLower; i++) pattern.add("SIDE_LOWER");
        for (int i = 0; i < sideUpper; i++) pattern.add("SIDE_UPPER");
        return List.copyOf(pattern);
    }

    /**
     * The domain travel class this layout describes.
     *
     * <p>Delegated rather than redefined. This enum previously carried its own
     * copy of the codes ("SL", "3A", ...), which made two sources of truth for a
     * value that also appears in the V2 and V3 CHECK constraints. A mismatch
     * between them is an insert failure at runtime, not a compile error, so the
     * duplication was worth removing the moment a domain enum existed.
     */
    public TravelClass travelClass() {
        return switch (this) {
            case SL -> TravelClass.SL;
            case THREE_A -> TravelClass.AC3;
            case TWO_A -> TravelClass.AC2;
            case ONE_A -> TravelClass.AC1;
        };
    }

    public int berthCount() {
        return bays * bayPattern.size();
    }

    /**
     * Berth types in ordinal order, bay by bay.
     *
     * <p>Order matters beyond presentation: allocation walks berths "ordered by
     * ordinal" (Appendix A), so this sequence is part of the algorithm's
     * observable behaviour. T-7 asserts the Java and Lua implementations pick the
     * <em>same</em> berth, not merely an equally valid one.
     */
    public List<String> berthTypes() {
        var types = new ArrayList<String>(berthCount());
        for (int b = 0; b < bays; b++) {
            types.addAll(bayPattern);
        }
        return types;
    }

    /** Drives FR-38's RAC allowance of {@code 2 x side_lower_berth_count}. */
    public int sideLowerCount() {
        return (int) bayPattern.stream().filter("SIDE_LOWER"::equals).count() * bays;
    }

    /** RAC capacity for one coach of this class (FR-38). */
    public int racAllowance() {
        return 2 * sideLowerCount();
    }
}
