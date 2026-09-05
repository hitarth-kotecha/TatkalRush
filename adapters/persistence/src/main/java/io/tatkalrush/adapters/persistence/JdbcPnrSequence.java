package io.tatkalrush.adapters.persistence;

import io.tatkalrush.application.ports.PnrSequence;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code pnr_seq} (FR-26, migration V9).
 *
 * <p>{@code nextval} is deliberately <b>not</b> transactional. Postgres never rolls
 * a sequence back, because doing so would mean holding a lock on it for the length
 * of every transaction that touched it — serialising every writer on the one
 * counter, at exactly the moment a Tatkal spike has thousands of them.
 *
 * <p>The consequence is gaps: a confirmation that rolls back consumes a value
 * nobody ever sees. That is the correct trade. Gaps in issued PNRs are not lost
 * bookings and no invariant checks for contiguity — INV-6 recomputes the check
 * digit of the PNRs that exist, and says nothing about the ones that do not.
 */
public final class JdbcPnrSequence implements PnrSequence {

    private final JdbcClient jdbc;

    public JdbcPnrSequence(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public long next() {
        return jdbc.sql("SELECT nextval('pnr_seq')").query(Long.class).single();
    }
}
