package io.tatkalrush.ops.warmup;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.tatkalrush.adapters.allocatorredis.RedisSeatAllocator;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Command line for §13.4's rebuild.
 *
 * <pre>
 *   mvn -q -pl ops/pool-warmup -am compile exec:java \
 *       -Dexec.mainClass=io.tatkalrush.ops.warmup.PoolWarmupMain \
 *       -Dexec.args="jdbc:postgresql://localhost:5432/tatkal tatkal tatkal localhost 6379"
 * </pre>
 *
 * <p>Run after seeding, and again after chaos scenario C2 flushes Redis. It is
 * idempotent — {@code init-pool.lua} writes the whole pool state rather than
 * mutating it, so running twice produces the same bytes as running once.
 */
public final class PoolWarmupMain {

    private PoolWarmupMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                    "usage: PoolWarmupMain <jdbcUrl> <user> <password> [redisHost] [redisPort]");
            System.exit(2);
        }

        String redisHost = args.length > 3 ? args[3] : "localhost";
        int redisPort = args.length > 4 ? Integer.parseInt(args[4]) : 6379;

        System.out.printf("Rebuilding pool state: %s -> redis://%s:%d%n%n",
                args[0], redisHost, redisPort);

        RedisClient client = RedisClient.create(RedisURI.create(redisHost, redisPort));
        try (var redis = client.connect();
                Connection conn = DriverManager.getConnection(args[0], args[1], args[2])) {

            var result =
                    new PoolRebuilder(conn, new RedisSeatAllocator(redis.sync())).rebuildAll();

            System.out.printf(
                    "  pools provisioned      : %,d%n"
                            + "  berth slots            : %,d%n"
                            + "  allocations replayed   : %,d%n"
                            + "  elapsed                : %,d ms%n",
                    result.pools(), result.berths(), result.allocationsReplayed(),
                    result.elapsedMillis());

            if (result.pools() == 0) {
                // Silence here would look like success and leave every hold in the
                // run that follows failing with "pool not provisioned".
                System.err.println(
                        "\nNo pools provisioned. Either the database is not seeded, or every"
                                + " schedule is DEPARTED/CANCELLED.");
                System.exit(1);
            }

            if (!result.shapeWarnings().isEmpty()) {
                System.out.printf("%n  %d shape warning(s):%n", result.shapeWarnings().size());
                result.shapeWarnings().stream().limit(20)
                        .forEach(w -> System.out.println("    - " + w));
                if (result.shapeWarnings().size() > 20) {
                    System.out.printf(
                            "    ... and %d more%n", result.shapeWarnings().size() - 20);
                }
            }
        } finally {
            client.shutdown();
        }
    }
}
