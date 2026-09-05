package io.tatkalrush.adapters.persistence;

import io.tatkalrush.application.ports.BookingRepository;
import io.tatkalrush.domain.booking.BookingStatus;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * {@link BookingRepository} on Postgres.
 *
 * <p>Three things here are load-bearing rather than mechanical, and each is the
 * discharge of a contract the port states in prose:
 *
 * <ol>
 *   <li>{@link #findByIdForUpdate} really takes a row lock, so the two settlement
 *       routes queue instead of racing.
 *   <li>{@link #persistAllocations} checks before inserting <em>and</em> wraps the
 *       insert in a {@code SAVEPOINT}, so a constraint violation is an answer
 *       rather than a poisoned transaction.
 *   <li>Every state change is a compare-and-set on {@code status}, so "did I win?"
 *       is answered by the database rather than by a preceding read.
 * </ol>
 *
 * <p>{@code JdbcClient} resolves its connection through Spring's
 * {@code DataSourceUtils}, so every statement below joins whatever transaction
 * {@link SpringUnitOfWork} opened — which is what makes the locks above shared
 * rather than mutually blocking.
 */
public final class JdbcBookingRepository implements BookingRepository {

    /** Postgres: {@code exclusion_violation}. */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    public JdbcBookingRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    // ── writes on the hold path ─────────────────────────────────────────────

    @Override
    public long createHeld(NewHeldBooking booking) {
        long bookingId =
                jdbc.sql(
                                """
                                INSERT INTO bookings (
                                    schedule_id, travel_class, quota_type,
                                    from_seq, to_seq, status, booking_class,
                                    passenger_count, fare_paise, user_id,
                                    hold_expires_at, idempotency_key)
                                VALUES (?, ?, ?, ?, ?, 'HELD', 'CNF', ?, ?, ?, ?, ?)
                                RETURNING id
                                """)
                        .param(booking.pool().scheduleId())
                        .param(booking.pool().travelClass().code())
                        .param(booking.pool().quotaType().name())
                        .param(booking.range().fromSeq())
                        .param(booking.range().toSeq())
                        .param(booking.passengerCount())
                        .param(booking.farePaise())
                        .param(booking.userId())
                        .param(Timestamp.from(booking.holdExpiresAt()))
                        .param(booking.idempotencyKey())
                        .query(Long.class)
                        .single();

        // Passenger i holds berth i (NewHeldBooking enforces the pairing). This is
        // where a held berth actually lives: passengers.berth_id is the only
        // column that records it before confirmation writes seat_allocations.
        var passengers = booking.passengers();
        for (int i = 0; i < passengers.size(); i++) {
            var passenger = passengers.get(i);
            jdbc.sql(
                            """
                            INSERT INTO passengers (booking_id, name, age, gender, berth_id)
                            VALUES (?, ?, ?, ?, ?)
                            """)
                    .param(bookingId)
                    .param(passenger.name())
                    .param(passenger.age())
                    .param(passenger.gender().name())
                    .param(booking.berthIds().get(i))
                    .update();
        }

        return bookingId;
    }

    // ── reads ───────────────────────────────────────────────────────────────

    private static final String SELECT_BOOKING =
            """
            SELECT id, pnr, status, schedule_id, travel_class, quota_type,
                   from_seq, to_seq, passenger_count, fare_paise, user_id,
                   hold_expires_at
            FROM bookings WHERE id = ?
            """;

    @Override
    public Optional<BookingView> findById(long bookingId) {
        return jdbc.sql(SELECT_BOOKING).param(bookingId).query(this::toView).optional();
    }

    @Override
    public Optional<BookingView> findByIdForUpdate(long bookingId) {
        // FOR UPDATE, and the port explains at length why. A second caller reaching
        // this statement blocks until the first transaction ends, rather than
        // reading PAYMENT_PENDING and proceeding to insert allocations that overlap
        // the first one's own rows.
        return jdbc.sql(SELECT_BOOKING + " FOR UPDATE")
                .param(bookingId)
                .query(this::toView)
                .optional();
    }

    @Override
    public int countActiveHolds(long userId, Instant now) {
        return jdbc.sql(
                        """
                        SELECT count(*) FROM bookings
                        WHERE user_id = ?
                          AND status IN ('HELD', 'PAYMENT_PENDING')
                          AND hold_expires_at > ?
                        """)
                .param(userId)
                .param(Timestamp.from(now))
                .query(Integer.class)
                .single();
    }

    private BookingView toView(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        Timestamp expiry = rs.getTimestamp("hold_expires_at");

        return new BookingView(
                id,
                Optional.ofNullable(rs.getString("pnr")),
                BookingStatus.valueOf(rs.getString("status")),
                new PoolKey(
                        rs.getLong("schedule_id"),
                        TravelClass.fromCode(rs.getString("travel_class")),
                        QuotaType.valueOf(rs.getString("quota_type"))),
                new SegmentRange(rs.getInt("from_seq"), rs.getInt("to_seq")),
                rs.getInt("passenger_count"),
                rs.getLong("fare_paise"),
                rs.getLong("user_id"),
                Optional.ofNullable(expiry).map(Timestamp::toInstant),
                berthIdsOf(id));
    }

    private List<Long> berthIdsOf(long bookingId) {
        // Ordered by passenger id so the berths come back in the order they were
        // assigned. Confirmation writes seat_allocations from this list, and an
        // unstable order would make two runs of the same booking produce different
        // rows - which FR-50 forbids for the strategy comparison.
        return jdbc.sql(
                        """
                        SELECT berth_id FROM passengers
                        WHERE booking_id = ? AND berth_id IS NOT NULL
                        ORDER BY id
                        """)
                .param(bookingId)
                .query(Long.class)
                .list();
    }

    // ── FR-25 step 2 ────────────────────────────────────────────────────────

    @Override
    public AllocationOutcome persistAllocations(
            long bookingId, long scheduleId, SegmentRange range, List<Long> berthIds) {

        // Contract part one: check before inserting. Safe ONLY because the caller
        // holds this booking's row lock. The database cannot make this distinction
        // for us - a duplicate's second insert overlaps its own first rows and
        // raises the same 23P01 an allocator defect raises.
        boolean present =
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM seat_allocations WHERE booking_id = ?)")
                        .param(bookingId)
                        .query(Boolean.class)
                        .single();
        if (present) {
            return new AllocationOutcome.AlreadyPresent();
        }

        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint = null;
        try {
            // Contract part two: return, do not throw. In Postgres one failed
            // statement aborts the whole transaction - every subsequent statement
            // answers 25P02 until rollback - so without this savepoint the caller
            // could not write the refund row it is about to decide on.
            savepoint = connection.setSavepoint("allocations");

            try (PreparedStatement insert =
                    connection.prepareStatement(
                            """
                            INSERT INTO seat_allocations
                                (schedule_id, berth_id, booking_id, seg_range)
                            VALUES (?, ?, ?, int4range(?, ?))
                            """)) {
                for (long berthId : berthIds) {
                    insert.setLong(1, scheduleId);
                    insert.setLong(2, berthId);
                    insert.setLong(3, bookingId);
                    insert.setInt(4, range.fromSeq());
                    insert.setInt(5, range.toSeq());
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            connection.releaseSavepoint(savepoint);
            return new AllocationOutcome.Persisted();

        } catch (SQLException e) {
            if (!isExclusionViolation(e)) {
                throw new UncategorizedSQLException(
                        "persisting allocations for booking " + bookingId,
                        "INSERT INTO seat_allocations",
                        e);
            }
            rollbackTo(connection, savepoint);
            return new AllocationOutcome.Conflict(findConflictingBerth(scheduleId, range, berthIds));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static boolean isExclusionViolation(SQLException e) {
        // Batch execution wraps the real cause in a BatchUpdateException, whose
        // own SQLState is often the generic 23000. The chain carries the specific
        // one, so walk it rather than trusting the outermost.
        for (SQLException current = e; current != null; current = current.getNextException()) {
            if (EXCLUSION_VIOLATION.equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void rollbackTo(Connection connection, Savepoint savepoint) {
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            // Rolling back to a savepoint is what makes the transaction usable
            // again. If that fails there is nothing left to salvage, and pretending
            // otherwise would let the caller write a refund row into a transaction
            // that cannot commit.
            throw new IllegalStateException("could not roll back to the allocation savepoint", e);
        }
    }

    /**
     * Which of our berths the constraint refused.
     *
     * <p>Asked as a query rather than parsed out of Postgres's {@code DETAIL} line.
     * That line does contain the conflicting key, and reading it would couple this
     * class to a message format that is version-dependent and localisable — for a
     * value that only ever appears in a run which is already failing under INV-11,
     * where being right matters and being fast does not.
     */
    private long findConflictingBerth(long scheduleId, SegmentRange range, List<Long> berthIds) {
        // Placeholders expanded rather than a bound array: JdbcClient has no
        // portable binding for SQL arrays, and berthIds is 1..6 entries.
        String placeholders = String.join(", ", java.util.Collections.nCopies(berthIds.size(), "?"));

        var statement =
                jdbc.sql(
                                """
                                SELECT berth_id FROM seat_allocations
                                WHERE schedule_id = ?
                                  AND berth_id IN (%s)
                                  AND seg_range && int4range(?, ?)
                                ORDER BY berth_id
                                LIMIT 1
                                """
                                        .formatted(placeholders))
                        .param(scheduleId);
        for (long berthId : berthIds) {
            statement = statement.param(berthId);
        }

        return statement
                .param(range.fromSeq())
                .param(range.toSeq())
                .query(Long.class)
                .optional()
                // The constraint fired, so a conflicting row existed a moment ago.
                // Not finding it now means it was rolled back by its own writer
                // between the two statements - real, and still worth reporting
                // rather than crashing on top of an already-failing run.
                .orElse(-1L);
    }

    // ── state transitions, each a compare-and-set ───────────────────────────

    // The `at` these three receive is not stored: §10.1 gives bookings a
    // confirmed_at and a cancelled_at but no failed_at, and inventing a column to
    // hold a timestamp nothing reads would be schema for its own sake. When the
    // moment matters it is recoverable from payments.settled_at, which is where
    // the event that caused the transition is already recorded.

    @Override
    public boolean beginPayment(long bookingId, Instant at) {
        return transition(bookingId, BookingStatus.HELD, BookingStatus.PAYMENT_PENDING);
    }

    @Override
    public boolean markFailed(long bookingId, Instant at) {
        return transition(bookingId, BookingStatus.PAYMENT_PENDING, BookingStatus.FAILED);
    }

    @Override
    public boolean markFailedRefunded(long bookingId, Instant at) {
        return transition(bookingId, BookingStatus.PAYMENT_PENDING, BookingStatus.FAILED_REFUNDED);
    }

    @Override
    public boolean confirm(long bookingId, String pnr, Instant confirmedAt) {
        int updated =
                jdbc.sql(
                                """
                                UPDATE bookings
                                SET status = 'CONFIRMED', pnr = ?, confirmed_at = ?
                                WHERE id = ? AND status = 'PAYMENT_PENDING'
                                """)
                        .param(pnr)
                        .param(Timestamp.from(confirmedAt))
                        .param(bookingId)
                        .update();
        return updated == 1;
    }

    /**
     * {@code UPDATE ... WHERE status = ?}, returning whether it applied.
     *
     * <p>The {@code WHERE} clause is the whole mechanism. Reading the status and
     * then updating unconditionally would let two callers both observe
     * {@code PAYMENT_PENDING} and both proceed; here the second one updates zero
     * rows and is told so.
     */
    private boolean transition(long bookingId, BookingStatus from, BookingStatus to) {
        // Validates THIS METHOD'S declared pair against FR-27's diagram, not the
        // booking's state. It can only fire if someone edits a caller to name an
        // illegal pair - in which case it fires on the first call, naming both
        // states, rather than leaving the status column holding something nothing
        // downstream knows how to handle. What protects a particular row is the
        // WHERE clause below.
        from.requireTransitionTo(to);

        int updated =
                jdbc.sql("UPDATE bookings SET status = ? WHERE id = ? AND status = ?")
                        .param(to.name())
                        .param(bookingId)
                        .param(from.name())
                        .update();
        return updated == 1;
    }
}
