package io.tatkalrush.ops.warmup;

import io.tatkalrush.adapters.allocatorswp.JdbcCheckpointStore;
import io.tatkalrush.adapters.allocatorswp.JdbcHoldRoutingStore;
import io.tatkalrush.adapters.allocatorswp.KafkaSeatAllocator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Command line for Strategy B's warmup (§9.3, milestone 7).
 *
 * <pre>
 *   mvn -q -pl ops/pool-warmup -am compile exec:java \
 *       -Dexec.mainClass=io.tatkalrush.ops.warmup.SwpPoolWarmupMain \
 *       -Dexec.args="jdbc:postgresql://localhost:5432/tatkal tatkal tatkal localhost:9092"
 * </pre>
 *
 * <p>Run once, after seeding, before the first booking — see
 * {@link SwpPoolProvisioner}'s class Javadoc for why this has no chaos-recovery
 * use the way {@link PoolWarmupMain} does. Safe to run again by accident: a
 * pool that already has confirmed bookings is provisioned with that occupancy
 * intact, not wiped.
 *
 * <p>Assumes {@code booking-commands}, {@code booking-events} and
 * {@code booking-replies} already exist on the broker — this tool does not
 * create topics, the same way it does not run Flyway.
 */
public final class SwpPoolWarmupMain {

    private SwpPoolWarmupMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println(
                    "usage: SwpPoolWarmupMain <jdbcUrl> <user> <password> <kafkaBootstrapServers>"
                            + " [commandsTopic] [eventsTopic] [repliesTopic]");
            System.exit(2);
        }

        String commandsTopic = args.length > 4 ? args[4] : "booking-commands";
        String eventsTopic = args.length > 5 ? args[5] : "booking-events";
        String repliesTopic = args.length > 6 ? args[6] : "booking-replies";

        System.out.printf(
                "Provisioning Strategy B pools: %s -> kafka://%s (%s/%s/%s)%n%n",
                args[0], args[3], commandsTopic, eventsTopic, repliesTopic);

        // DriverManagerDataSource, not SimpleDriverDataSource: the postgres
        // driver is runtime-scoped in this module's pom (mirroring
        // PoolWarmupMain, which never references org.postgresql.Driver either),
        // and DriverManager finds it via its own SPI discovery at runtime
        // without this class needing a compile-time reference to it.
        DataSource dataSource = new DriverManagerDataSource(args[0], args[1], args[2]);

        var allocator =
                new KafkaSeatAllocator(
                        args[3],
                        commandsTopic,
                        eventsTopic,
                        repliesTopic,
                        Duration.ofSeconds(30),
                        new JdbcCheckpointStore(dataSource),
                        new JdbcHoldRoutingStore(dataSource));
        allocator.start();

        try (Connection conn = DriverManager.getConnection(args[0], args[1], args[2])) {
            var result = new SwpPoolProvisioner(conn, allocator).provisionAll();

            System.out.printf(
                    "  pools provisioned      : %,d%n"
                            + "  berth slots            : %,d%n"
                            + "  allocations replayed   : %,d%n"
                            + "  elapsed                : %,d ms%n",
                    result.pools(), result.berths(), result.allocationsReplayed(),
                    result.elapsedMillis());

            if (result.pools() == 0) {
                // Silence here would look like success and leave every hold in
                // the run that follows failing with "pool not provisioned".
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
            allocator.close();
        }
    }
}
