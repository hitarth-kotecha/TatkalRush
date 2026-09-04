package io.tatkalrush.ops.seed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Command-line entry point for seeding.
 *
 * <pre>
 *   mvn -q -pl ops/seed -am compile exec:java \
 *       -Dexec.mainClass=io.tatkalrush.ops.seed.SeedMain \
 *       -Dexec.args="jdbc:postgresql://localhost:5432/tatkal tatkal tatkal"
 * </pre>
 *
 * <p>Plain JDBC rather than a Spring context: seeding is a batch job with no
 * dependency on anything the application wires up, and starting a full context
 * would add several seconds to AC-0.2's 60-second budget for no benefit.
 */
public final class SeedMain {

    private SeedMain() {}

    public static void main(String[] args) throws SQLException {
        if (args.length < 3) {
            System.err.println("usage: SeedMain <jdbcUrl> <user> <password> [seed]");
            System.exit(2);
        }

        String url = withBatchRewrite(args[0]);
        long seed = args.length > 3 ? Long.parseLong(args[3]) : SeedConfig.DEFAULT_SEED;

        var config = new SeedConfig(seed, 20, 5_000);

        System.out.printf("Seeding %s (seed=%d)%n%n", args[0], seed);

        try (Connection conn = DriverManager.getConnection(url, args[1], args[2])) {
            SeedStats stats = new SeedGenerator(config).generate(conn);
            System.out.println(stats);

            if (stats.elapsedMillis > 60_000) {
                System.err.println("\nAC-0.2 FAILED: exceeded the 60 s budget");
                System.exit(1);
            }
        }
    }

    /**
     * Appends {@code reWriteBatchedInserts=true}, which lets the PostgreSQL
     * driver collapse a batch of single-row INSERTs into one multi-row INSERT.
     * With ~345k pool_berths rows this is the difference between comfortably
     * inside AC-0.2's budget and uncomfortably near it.
     */
    static String withBatchRewrite(String jdbcUrl) {
        if (jdbcUrl.contains("reWriteBatchedInserts")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "reWriteBatchedInserts=true";
    }
}
