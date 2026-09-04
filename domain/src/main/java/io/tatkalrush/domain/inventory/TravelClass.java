package io.tatkalrush.domain.inventory;

/**
 * Travel class (§5.1).
 *
 * <p>The enum constant and the stored code differ because {@code 3A} is not a
 * legal Java identifier. {@link #code()} is the single source of truth for what
 * appears in the database, and the {@code CHECK} constraints in migration V2 and
 * V3 must list exactly these values — a mismatch there is a runtime insert
 * failure rather than a compile error, so {@link #fromCode} exists to make the
 * round trip explicit and testable.
 */
public enum TravelClass {

    /** Sleeper. 9 bays of 8; the bulk of an Indian Railways consist. */
    SL("SL"),

    /** 3-tier AC. */
    AC3("3A"),

    /** 2-tier AC. No middle berth. */
    AC2("2A"),

    /** 1st AC. Cabins, and therefore no side berths and no RAC quota (FR-38). */
    AC1("1A"),

    /** Chair car. Seats rather than berths; not seeded (DD-026). */
    CC("CC");

    private final String code;

    TravelClass(String code) {
        this.code = code;
    }

    /** The value stored in {@code travel_class} columns. */
    public String code() {
        return code;
    }

    public static TravelClass fromCode(String code) {
        for (TravelClass c : values()) {
            if (c.code.equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown travel class code: " + code);
    }
}
