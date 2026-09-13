package io.tatkalrush.adapters.persistence;

import io.tatkalrush.application.ports.HoldExpiry;
import io.tatkalrush.domain.booking.BookingStatus;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link HoldExpiry} as one batched compare-and-set.
 *
 * <p>The subquery picks the oldest lapsed holds through {@code idx_bookings_live_holds}
 * — partial on {@code HELD}/{@code PAYMENT_PENDING}, keyed on {@code hold_expires_at},
 * so it stays the size of the live set however many terminal bookings a soak
 * accumulates. {@code FOR UPDATE SKIP LOCKED} takes the row locks in the same
 * statement that updates, and re-evaluates {@code status = 'HELD'} once each lock is
 * held, so a booking that moved to {@code PAYMENT_PENDING} between the scan and the
 * lock is left alone.
 */
public final class JdbcHoldExpiry implements HoldExpiry {

    private final JdbcClient jdbc;

    public JdbcHoldExpiry(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public int expireLapsed(Instant now, int limit) {
        // The same guard JdbcBookingRepository.transition applies: it validates this
        // method's declared edge against FR-27, and fires on the first call if
        // someone edits the SQL to name an illegal one.
        BookingStatus.HELD.requireTransitionTo(BookingStatus.EXPIRED);

        return jdbc.sql(
                        """
                        UPDATE bookings
                        SET status = 'EXPIRED', hold_expires_at = NULL
                        WHERE id IN (
                            SELECT id FROM bookings
                            WHERE status = 'HELD' AND hold_expires_at <= ?
                            ORDER BY hold_expires_at
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED)
                        """)
                .param(Timestamp.from(now))
                .param(limit)
                .update();
    }
}
