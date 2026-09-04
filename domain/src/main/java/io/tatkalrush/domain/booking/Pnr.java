package io.tatkalrush.domain.booking;

/**
 * A Passenger Name Record: ten digits, the last a Luhn check digit (FR-26).
 *
 * <p><b>Derived from a sequence, never generated randomly.</b> FR-26 forbids
 * random generation with collision retry, and the reason is this project's entire
 * subject. Random-with-retry degrades exactly under the load a Tatkal spike
 * creates: as the space fills, collisions rise, and the retry storm peaks at the
 * moment the system is already at its limit. A sequence plus a check digit is
 * O(1) forever, and the uniqueness comes from Postgres rather than from hope.
 *
 * <p><b>What the check digit is for.</b> It catches single-digit typos and most
 * adjacent transpositions — the mistakes a human makes reading a PNR aloud. It is
 * not security: anyone can compute a valid PNR. INV-6 recomputes it to verify no
 * stored PNR has been corrupted.
 */
public record Pnr(String value) {

    private static final int LENGTH = 10;
    private static final long MAX_SEQUENCE = 999_999_999L;

    public Pnr {
        if (value == null || value.length() != LENGTH) {
            throw new IllegalArgumentException(
                    "PNR must be exactly " + LENGTH + " digits, got: " + value);
        }
        for (int i = 0; i < LENGTH; i++) {
            if (!Character.isDigit(value.charAt(i))) {
                throw new IllegalArgumentException("PNR must be all digits, got: " + value);
            }
        }
        if (!passesLuhn(value)) {
            throw new IllegalArgumentException("PNR check digit is invalid: " + value);
        }
    }

    /**
     * Builds the PNR for a sequence value.
     *
     * @param sequence a Postgres sequence value, 1..999,999,999
     */
    public static Pnr fromSequence(long sequence) {
        if (sequence < 1 || sequence > MAX_SEQUENCE) {
            // Throwing rather than wrapping. Wrapping would reissue a PNR that
            // already belongs to another booking, and the unique index would then
            // fail a confirmation for a customer whose money is already captured.
            // At ~700 berths a train and 600 schedules, this bound is not
            // reachable by any run this project performs.
            throw new IllegalArgumentException(
                    "PNR sequence out of range 1.." + MAX_SEQUENCE + ", got " + sequence);
        }
        String body = "%09d".formatted(sequence);
        return new Pnr(body + checkDigitFor(body));
    }

    /** Whether a string is a well-formed PNR. Used by INV-6. */
    public static boolean isValid(String candidate) {
        if (candidate == null || candidate.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (!Character.isDigit(candidate.charAt(i))) {
                return false;
            }
        }
        return passesLuhn(candidate);
    }

    /** The sequence value this PNR was built from. */
    public long sequence() {
        return Long.parseLong(value.substring(0, LENGTH - 1));
    }

    // ------------------------------------------------------------------ Luhn

    /**
     * The digit that makes {@code body} satisfy Luhn once appended.
     *
     * <p>Computed as the amount needed to bring the checksum to a multiple of ten.
     */
    static char checkDigitFor(String body) {
        int sum = luhnSum(body + "0");
        int check = (10 - (sum % 10)) % 10;
        return (char) ('0' + check);
    }

    private static boolean passesLuhn(String digits) {
        return luhnSum(digits) % 10 == 0;
    }

    /**
     * Luhn's weighted digit sum.
     *
     * <p>Walking from the right, every second digit is doubled; a doubled value
     * over 9 has 9 subtracted, which is the same as summing its two digits. The
     * doubling is what makes the checksum sensitive to <em>order</em>, so a
     * transposition changes it — a plain sum would not notice.
     */
    private static int luhnSum(String digits) {
        int sum = 0;
        boolean doubling = false;

        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum;
    }

    @Override
    public String toString() {
        return value;
    }
}
