package io.tatkalrush.ops.invariants;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;

/**
 * Runs §14's twelve invariants against a live system.
 *
 * <p>Twelve checks, forty-one tests, and until this class existed <b>no way to
 * point any of them at the running stack</b> — which is precisely what AC-1.2
 * ("P1 completes with zero INV violations") and AC-1.3 both require. The third
 * time this project has found a capability with no caller, after
 * {@code provision()} and the {@code psp-sim} profile.
 *
 * <pre>
 *   java -jar ops/invariant-checker/target/invariant-checker.jar \
 *       jdbc:postgresql://localhost:5432/tatkal tatkal tatkal [redisHost] [redisPort] [--continuous]
 * </pre>
 *
 * <h2>Quiesced by default, and that is the important default</h2>
 *
 * <p>INV-5, INV-8 and INV-12 compare Redis against Postgres. A live hold makes
 * them legitimately disagree — masks carry it, {@code seat_allocations} does not
 * until confirmation (FR-25) — so run mid-load they report violations that are not
 * violations. §14 says quiesce first, and a checker whose easy default cries wolf
 * degrades the checks standing beside it.
 *
 * <p>{@code --continuous} is for running against a system under load, and skips
 * exactly those three. The report says how many were skipped, so "all green" is
 * never mistaken for "everything was asked".
 *
 * <h2>Exit code</h2>
 *
 * <p>0 when every check that ran passed, 1 otherwise. NFR-9 makes any violation
 * fail the build, so this is meant to be used as {@code &&} in a script rather
 * than read by a human who might not.
 */
public final class InvariantCheckerMain {

    private InvariantCheckerMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                    "usage: InvariantCheckerMain <jdbcUrl> <user> <password>"
                            + " [redisHost] [redisPort] [--continuous]");
            System.exit(2);
        }

        boolean continuous = false;
        String redisHost = "localhost";
        int redisPort = 6379;
        for (int i = 3; i < args.length; i++) {
            if ("--continuous".equals(args[i])) {
                continuous = true;
            } else if (i == 3) {
                redisHost = args[i];
            } else if (i == 4) {
                redisPort = Integer.parseInt(args[i]);
            }
        }

        var mode =
                continuous ? InvariantChecker.Mode.CONTINUOUS : InvariantChecker.Mode.QUIESCED;

        // FR-17's 120 s, which INV-5's grace period is measured from. Read from a
        // property rather than hardcoded because §19's profiles shorten it, and a
        // checker using the wrong TTL would report every recently-expired hold as
        // a leak.
        long holdTtlMillis = Long.getLong("tatkal.hold.ttl-ms", 120_000L);

        RedisClient client = RedisClient.create(RedisURI.create(redisHost, redisPort));
        // Not client.connect(): the default UTF-8 codec silently destroys every
        // mask and free-count byte at or above 0x80. See RedisInvariants.connect.
        try (var redis = RedisInvariants.connect(client);
                Connection conn = DriverManager.getConnection(args[0], args[1], args[2])) {

            var all = new ArrayList<Invariant>(SqlInvariants.all());
            all.addAll(RedisInvariants.all(redis.sync(), holdTtlMillis));

            var report = new InvariantChecker(all).run(conn, mode);
            System.out.print(report.render());

            if (!report.passed()) {
                System.exit(1);
            }
        } finally {
            client.shutdown();
        }
    }
}
