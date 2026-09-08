package io.tatkalrush.ops.invariants;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * What a check is given: a database connection and a row cap.
 *
 * <p>Deliberately not a {@code JdbcTemplate} or a repository. The checker is the
 * one component that must be able to disagree with the application, and handing it
 * the application's data access would make that impossible by construction.
 */
public final class CheckContext {

    /**
     * Rows any single invariant will report.
     *
     * <p>A real violation usually cascades — one double-allocated berth produces a
     * row per overlapping pair — and a report listing nine thousand of them is a
     * report nobody reads. Twenty is enough to see the shape, and the count is
     * always reported in full so the scale is never hidden.
     */
    public static final int MAX_ROWS_REPORTED = 20;

    private final Connection connection;

    public CheckContext(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Runs a query and renders each row as one line.
     *
     * <p>Every column is included. A check that selected only the ids would make
     * the report smaller and the investigation longer.
     */
    public List<String> query(String sql) {
        var rows = new ArrayList<String>();

        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {

            var metadata = rs.getMetaData();
            int columns = metadata.getColumnCount();
            long total = 0;

            while (rs.next()) {
                total++;
                if (rows.size() < MAX_ROWS_REPORTED) {
                    var line = new StringBuilder();
                    for (int i = 1; i <= columns; i++) {
                        if (i > 1) {
                            line.append(", ");
                        }
                        line.append(metadata.getColumnLabel(i)).append('=').append(rs.getString(i));
                    }
                    rows.add(line.toString());
                }
            }

            if (total > rows.size()) {
                // The count, not just the sample. "20 rows" and "9,412 rows" call
                // for very different responses, and truncating silently hides
                // which one you have.
                rows.add("... and %d more (%d violating rows in total)".formatted(total - rows.size(), total));
            }

        } catch (SQLException e) {
            // A check that cannot run is not a check that passed. Surfacing it as
            // a violation is deliberate: NFR-9 fails the build either way, and the
            // alternative - swallowing it and reporting green - is how a checker
            // silently stops checking.
            rows.add("CHECK FAILED TO EXECUTE: " + e.getMessage());
        }

        return rows;
    }
}
