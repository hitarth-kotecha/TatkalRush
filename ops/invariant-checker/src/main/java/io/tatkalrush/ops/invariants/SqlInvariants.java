package io.tatkalrush.ops.invariants;

import io.tatkalrush.domain.booking.Pnr;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * §14's invariants that live entirely in Postgres.
 *
 * <p>Each is a query written to return <b>violating rows</b>, so an empty result is
 * a pass. That direction matters: a query written to return "the correct rows" and
 * compared against an expected count would pass when it returned nothing because
 * the table was empty, which is exactly the failure mode of a run that crashed in
 * its first second.
 */
public final class SqlInvariants {

    private SqlInvariants() {}

    public static List<Invariant> all() {
        return List.of(
                noOverlappingAllocations(),
                everyConfirmedBookingHasOnePayment(),
                noOrphanedPayments(),
                allocationsWithinCapacity(),
                pnrsAreUniqueAndValid(),
                theLedgerBalances(),
                waitlistSequencesAreSane(),
                noTerminalBookingHoldsABerth(),
                noAllocationConflictRefunds());
    }

    // ── INV-1 ───────────────────────────────────────────────────────────────

    /**
     * The one the whole project is about.
     *
     * <p>The {@code EXCLUDE} constraint makes this impossible at the storage layer,
     * so this check should never fire — which is exactly why it is worth running.
     * It is the independent confirmation that the constraint is still there and
     * still doing what it claims: a migration that dropped it would leave every
     * other test passing.
     */
    public static Invariant noOverlappingAllocations() {
        return check(
                "INV-1",
                "No berth has two allocations with overlapping segment ranges",
                """
                SELECT a.id AS allocation_a, b.id AS allocation_b,
                       a.schedule_id, a.berth_id,
                       a.seg_range AS range_a, b.seg_range AS range_b,
                       a.booking_id AS booking_a, b.booking_id AS booking_b
                FROM seat_allocations a
                JOIN seat_allocations b
                  ON a.schedule_id = b.schedule_id
                 AND a.berth_id = b.berth_id
                 AND a.seg_range && b.seg_range
                 AND a.id < b.id
                """);
    }

    // ── INV-2 ───────────────────────────────────────────────────────────────

    public static Invariant everyConfirmedBookingHasOnePayment() {
        return check(
                "INV-2",
                "Every CONFIRMED booking has exactly one SUCCESS payment",
                """
                SELECT b.id AS booking_id, b.pnr, b.status,
                       count(p.id) AS success_payments
                FROM bookings b
                LEFT JOIN payments p
                  ON p.booking_id = b.id AND p.status IN ('SUCCESS', 'REFUNDED')
                WHERE b.status = 'CONFIRMED'
                GROUP BY b.id, b.pnr, b.status
                HAVING count(p.id) <> 1
                """);
    }

    // ── INV-3 ───────────────────────────────────────────────────────────────

    /**
     * Money captured with nothing to show for it.
     *
     * <p>{@code PAYMENT_PENDING} is deliberately <em>not</em> an accepted state
     * here. A settled payment on a booking that never advanced is precisely the
     * crash-between-settle-and-confirm case FR-23's sweep exists to repair, and
     * treating it as acceptable would make the sweep's failure invisible.
     */
    public static Invariant noOrphanedPayments() {
        return check(
                "INV-3",
                "No SUCCESS payment is orphaned - its booking is CONFIRMED, CANCELLED or FAILED_REFUNDED",
                """
                SELECT p.id AS payment_id, p.psp_payment_id, p.amount_paise,
                       b.id AS booking_id, b.status AS booking_status
                FROM payments p
                JOIN bookings b ON b.id = p.booking_id
                WHERE p.status IN ('SUCCESS', 'REFUNDED')
                  AND b.status NOT IN ('CONFIRMED', 'CANCELLED', 'FAILED_REFUNDED')
                """);
    }

    // ── INV-4 ───────────────────────────────────────────────────────────────

    /**
     * Capacity, per segment rather than per pool.
     *
     * <p>Per pool would be the wrong question: segment-wise inventory means a
     * 72-berth coach can hold far more than 72 bookings, as long as no <em>single
     * segment</em> carries more than 72. Counting bookings against capacity would
     * report T-3's complementary journeys - the capability this project exists to
     * demonstrate - as a violation.
     *
     * <p>{@code generate_series} expands each allocation into the segments it
     * actually occupies, which is what makes the count per-segment.
     */
    public static Invariant allocationsWithinCapacity() {
        return check(
                "INV-4",
                "For every (schedule, class, segment): CNF allocations <= pool capacity",
                """
                WITH occupied AS (
                    SELECT sa.schedule_id,
                           b.travel_class,
                           b.quota_type,
                           g.seg,
                           count(*) AS allocations
                    FROM seat_allocations sa
                    JOIN bookings b ON b.id = sa.booking_id
                    CROSS JOIN LATERAL generate_series(
                        lower(sa.seg_range), upper(sa.seg_range) - 1) AS g(seg)
                    GROUP BY sa.schedule_id, b.travel_class, b.quota_type, g.seg
                )
                SELECT o.schedule_id, o.travel_class, o.quota_type, o.seg,
                       o.allocations, q.total_berths AS capacity
                FROM occupied o
                JOIN quota_pools q
                  ON q.schedule_id = o.schedule_id
                 AND q.travel_class = o.travel_class
                 AND q.quota_type = o.quota_type
                WHERE o.allocations > q.total_berths
                """);
    }

    // ── INV-6 ───────────────────────────────────────────────────────────────

    /**
     * Uniqueness is a unique index; the check digit is recomputed here.
     *
     * <p>Recomputed rather than trusted, and recomputed in Java rather than SQL:
     * {@link Pnr} owns the Luhn algorithm, and a second implementation in SQL would
     * be a second thing to keep right. This is the one place the checker uses
     * domain code, and it is defensible because {@code Pnr} is a pure function of a
     * string — it cannot be wrong in a way that agrees with a corrupted value.
     */
    public static Invariant pnrsAreUniqueAndValid() {
        return new Invariant() {
            @Override
            public String id() {
                return "INV-6";
            }

            @Override
            public String description() {
                return "PNRs are unique and check digits valid";
            }

            @Override
            public List<String> violations(CheckContext context) {
                var violations =
                        new ArrayList<>(
                                context.query(
                                        """
                                        SELECT pnr, count(*) AS occurrences
                                        FROM bookings
                                        WHERE pnr IS NOT NULL
                                        GROUP BY pnr HAVING count(*) > 1
                                        """));

                try (Statement st = context.connection().createStatement();
                        ResultSet rs =
                                st.executeQuery(
                                        "SELECT id, pnr FROM bookings WHERE pnr IS NOT NULL")) {
                    while (rs.next()) {
                        String pnr = rs.getString("pnr");
                        try {
                            new Pnr(pnr);
                        } catch (IllegalArgumentException invalid) {
                            violations.add(
                                    "booking_id=%d, pnr=%s, reason=%s"
                                            .formatted(rs.getLong("id"), pnr, invalid.getMessage()));
                        }
                    }
                } catch (SQLException e) {
                    violations.add("CHECK FAILED TO EXECUTE: " + e.getMessage());
                }

                return violations;
            }
        };
    }

    // ── INV-7 ───────────────────────────────────────────────────────────────

    /**
     * The ledger balances against what was actually decided.
     *
     * <p>§14 asks for the retained fare "recomputed independently per FR-67b". The
     * independent recomputation of the <em>fare</em> belongs with the seed and rate
     * table; what this checks is the arithmetic that no amount of pricing logic can
     * excuse: for every booking, {@code sum(CHARGE) - sum(REFUND)} must equal the
     * refunds actually recorded against it, and must never be negative.
     *
     * <p>A negative retained amount means more money went out than came in. There
     * is no pricing rule under which that is correct, so it needs no rate table to
     * detect — which makes it the strongest half of this check and the half worth
     * having first.
     */
    public static Invariant theLedgerBalances() {
        return check(
                "INV-7",
                "sum(CHARGE) - sum(REFUND) is non-negative and matches the recorded refunds",
                """
                WITH ledger AS (
                    SELECT booking_id,
                           coalesce(sum(amount_paise) FILTER (WHERE entry_type = 'CHARGE'), 0)
                               AS charged,
                           coalesce(sum(amount_paise) FILTER (WHERE entry_type = 'REFUND'), 0)
                               AS refunded
                    FROM ledger_entries
                    GROUP BY booking_id
                ),
                recorded AS (
                    SELECT booking_id,
                           coalesce(sum(amount_paise) FILTER (WHERE status = 'COMPLETED'), 0)
                               AS completed_refunds
                    FROM refunds
                    GROUP BY booking_id
                )
                SELECT l.booking_id, l.charged, l.refunded,
                       coalesce(r.completed_refunds, 0) AS completed_refunds,
                       l.charged - l.refunded AS retained
                FROM ledger l
                LEFT JOIN recorded r ON r.booking_id = l.booking_id
                WHERE l.charged - l.refunded < 0
                   OR l.refunded <> coalesce(r.completed_refunds, 0)
                """);
    }

    // ── INV-9 ───────────────────────────────────────────────────────────────

    /**
     * Rewritten in v1.2 (DD-011).
     *
     * <p>It previously required stored positions to be contiguous, which FR-41's
     * out-of-order promotion makes impossible. What remains is what is actually
     * required: {@code seq} unique and strictly increasing per partition, and no
     * <em>active</em> entry carrying a {@code promoted_at}.
     *
     * <p>Only the uniqueness half is implemented, and deliberately. §14's second
     * clause - "no <em>active</em> entry has {@code promoted_at} set" - is
     * tautological under §10.4, where active IS {@code promoted_at IS NULL}: the
     * partial index defines the active queue that way. Writing a query for it would
     * produce a check that can never fail, which is worse than no check because it
     * looks like coverage.
     *
     * <p>Passes trivially today because the waitlist is Phase 3a and the table is
     * empty. Included anyway: a check written when the table fills is a check
     * written by someone already debugging. Phase 3a should revisit whether §14
     * meant something the schema does not currently express.
     */
    public static Invariant waitlistSequencesAreSane() {
        return check(
                "INV-9",
                "Waitlist seq is unique per (schedule, class, type), and no active entry is promoted",
                """
                SELECT schedule_id, travel_class, entry_type, seq,
                       count(*) AS occurrences
                FROM waitlist_entries
                GROUP BY schedule_id, travel_class, entry_type, seq
                HAVING count(*) > 1
                """);
    }

    // ── INV-10 ──────────────────────────────────────────────────────────────

    /**
     * A terminal booking with a live hold is a berth nobody can sell.
     *
     * <p>{@code CONFIRMED} is excluded: it is not terminal (it can still be
     * cancelled) and its berths are an allocation rather than a hold.
     *
     * <p><b>Writing this check found a bug.</b> Terminal transitions left
     * {@code hold_expires_at} populated, so a hold released at t=30 s with an
     * expiry at t=120 s produced an {@code EXPIRED} booking with a future hold
     * expiry - and this check would have fired on entirely correct behaviour. A
     * check that fires on correct behaviour gets disabled, and takes the checks
     * standing next to it with it. The transitions now clear the column.
     */
    public static Invariant noTerminalBookingHoldsABerth() {
        return check(
                "INV-10",
                "No booking in a terminal state has an active hold",
                """
                SELECT id AS booking_id, status, hold_expires_at
                FROM bookings
                WHERE status IN ('CANCELLED', 'EXPIRED', 'FAILED', 'FAILED_REFUNDED')
                  AND hold_expires_at IS NOT NULL
                """);
    }

    // ── INV-11 ──────────────────────────────────────────────────────────────

    /**
     * The one that fails the run on a single row.
     *
     * <p>Not a threshold and not a rate. An {@code ALLOCATION_CONFLICT} refund means
     * the exclusion constraint rejected an insert against a <em>live</em> hold — an
     * allocator sold one berth twice and the customer had already paid (DD-008).
     * One is as bad as a thousand.
     */
    public static Invariant noAllocationConflictRefunds() {
        return check(
                "INV-11",
                "No refund exists with reason = 'ALLOCATION_CONFLICT'",
                """
                SELECT id AS refund_id, booking_id, payment_id, amount_paise, created_at
                FROM refunds
                WHERE reason = 'ALLOCATION_CONFLICT'
                """);
    }

    // ── helper ──────────────────────────────────────────────────────────────

    private static Invariant check(String id, String description, String sql) {
        return new Invariant() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public List<String> violations(CheckContext context) {
                return context.query(sql);
            }
        };
    }
}
