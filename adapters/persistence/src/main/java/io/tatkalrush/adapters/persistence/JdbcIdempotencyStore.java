package io.tatkalrush.adapters.persistence;

import io.tatkalrush.application.ports.IdempotencyStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;

/**
 * FR-19's insert-first idempotency, on Postgres.
 *
 * <p>The whole mechanism is one {@code INSERT} and Postgres's behaviour around a
 * unique index. Concurrent inserts of the same key <b>block</b> rather than
 * failing: all but one wait until the first transaction commits or rolls back.
 * That wait is what makes check-then-act unnecessary, and it is why
 * {@link #claim} must run inside the caller's transaction — the lock other
 * callers wait on is this statement's.
 *
 * <p>{@code ON CONFLICT DO NOTHING} is used rather than catching a
 * {@code SQLIntegrityConstraintViolationException}, for a specific reason: with
 * {@code DO NOTHING} the conflicting statement still takes the index lock and
 * still waits for the incumbent transaction, but returns zero rows instead of
 * raising. Catching the exception would work equally well for correctness and
 * would poison the caller's transaction on some drivers, forcing a rollback the
 * caller did not ask for.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    public JdbcIdempotencyStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public Claim claim(String key, long userId, String requestHash) {
        // Inserted BEFORE any allocation happens (FR-19). Returns zero rows if
        // the key is taken - after having waited for whoever holds it.
        int inserted =
                jdbc.sql(
                                """
                                INSERT INTO idempotency_keys (key, user_id, request_hash)
                                VALUES (?, ?, ?)
                                ON CONFLICT (key) DO NOTHING
                                """)
                        .param(key)
                        .param(userId)
                        .param(requestHash)
                        .update();

        if (inserted == 1) {
            return new Claim.Won();
        }

        // Lost the race. Whoever won has committed by now - that is what released
        // this statement - so their row is visible and final.
        return jdbc.sql("SELECT request_hash, booking_id FROM idempotency_keys WHERE key = ?")
                .param(key)
                .query(
                        (ResultSet rs, int rowNum) -> {
                            String existingHash = rs.getString("request_hash");

                            // The same key with different content is a client bug,
                            // not a retry. Answering it with the first request's
                            // booking would confirm the wrong journey silently.
                            if (!existingHash.equals(requestHash)) {
                                return (Claim) new Claim.Reused(existingHash);
                            }

                            long bookingId = rs.getLong("booking_id");
                            if (rs.wasNull()) {
                                // Claimed but not completed: the winner failed
                                // after claiming, or is still working in a
                                // transaction this one cannot see. Either way this
                                // caller cannot answer, and must not allocate.
                                return (Claim) new Claim.Pending();
                            }
                            return (Claim) new Claim.Duplicate(bookingId);
                        })
                .optional()
                // Unreachable in practice: the insert conflicted, so a row exists.
                // Treated as Pending rather than asserted, because the alternative
                // is throwing on a path a retry would resolve.
                .orElseGet(Claim.Pending::new);
    }

    @Override
    public void complete(String key, long bookingId) {
        int updated =
                jdbc.sql("UPDATE idempotency_keys SET booking_id = ? WHERE key = ?")
                        .param(bookingId)
                        .param(key)
                        .update();

        if (updated != 1) {
            // The claim vanished between claiming and completing, which no code
            // path should produce. Failing loudly beats leaving a booking with no
            // idempotency record - every retry of that request would then allocate
            // a second set of berths.
            throw new IllegalStateException(
                    "idempotency key disappeared before completion: " + key);
        }
    }

    @Override
    public Optional<Long> bookingIdFor(String key) {
        return jdbc.sql("SELECT booking_id FROM idempotency_keys WHERE key = ?")
                .param(key)
                .query(Long.class)
                .optional();
    }

    /**
     * Runs {@code work} in one transaction, with the connection the claim must
     * share.
     *
     * <p>Exposed because FR-19's guarantee depends on the claim and the booking
     * insert committing together. A claim committed early would release waiting
     * callers before the booking exists, and they would read a {@code Pending}
     * that never resolves.
     */
    public <T> T inTransaction(TransactionalWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    /** Work performed inside a single transaction. */
    @FunctionalInterface
    public interface TransactionalWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    // Kept so a caller holding its own Connection can participate in the same
    // transaction as the claim. Spring's JdbcClient binds to the DataSource, and
    // the T-5 harness needs to prove the blocking behaviour with explicit
    // connections rather than ambient transaction management.
    static Claim claimOn(Connection connection, String key, long userId, String requestHash)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        """
                        INSERT INTO idempotency_keys (key, user_id, request_hash)
                        VALUES (?, ?, ?)
                        ON CONFLICT (key) DO NOTHING
                        """)) {
            insert.setString(1, key);
            insert.setLong(2, userId);
            insert.setString(3, requestHash);
            if (insert.executeUpdate() == 1) {
                return new Claim.Won();
            }
        }

        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT request_hash, booking_id FROM idempotency_keys WHERE key = ?")) {
            select.setString(1, key);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return new Claim.Pending();
                }
                String existingHash = rs.getString("request_hash");
                if (!existingHash.equals(requestHash)) {
                    return new Claim.Reused(existingHash);
                }
                long bookingId = rs.getLong("booking_id");
                return rs.wasNull()
                        ? new Claim.Pending()
                        : new Claim.Duplicate(bookingId);
            }
        }
    }

    static void completeOn(Connection connection, String key, long bookingId)
            throws SQLException {
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE idempotency_keys SET booking_id = ? WHERE key = ?")) {
            update.setLong(1, bookingId);
            update.setString(2, key);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "idempotency key disappeared before completion: " + key);
            }
        }
    }

    @SuppressWarnings("unused")
    private static final SQLExceptionSubclassTranslator TRANSLATOR =
            new SQLExceptionSubclassTranslator();
}
