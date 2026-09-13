package io.tatkalrush;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.tatkalrush.application.usecases.ExpireHolds;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * §13.2: runs {@link ExpireHolds} every five seconds on each replica, behind a lease.
 *
 * <h2>Not a correctness dependency</h2>
 *
 * <p>Allocation reaps its own pool (§9.2), so if this class never runs, no seat is
 * ever lost. It exists so that pools nobody is booking from give their expired
 * holds back. §13.2 requires that distinction to be stated in code, and the log
 * line on failure states it again, because the first person to see a reaper fail
 * will want to know how worried to be.
 *
 * <h2>The lease is about duplicated work, not safety</h2>
 *
 * <p>§13.2 says "a Redis lock so only one replica reaps a given partition". This
 * takes one lease per <em>sweep</em> rather than one per pool: {@code SET NX PX}
 * on a single key. A lock per pool would be up to 3,600 round trips every five
 * seconds to protect an operation that is already atomic per pool and idempotent
 * — two replicas reaping the same pool free its berths once. So the lease stops
 * both replicas doing the same sweep, and nothing depends on it beyond that. Its
 * TTL is shorter than the interval, so a replica that dies holding it costs one
 * sweep, not a stuck reaper. Recorded in DD-046.
 *
 * <h2>Why every failure is caught</h2>
 *
 * <p>{@link ScheduledExecutorService#scheduleWithFixedDelay} cancels a task the
 * first time it throws, and says so to nobody: the future is never read, and the
 * reaper simply stops. That is the "stalled reaper" §9.2's design survives, and
 * it would arrive through the first Redis timeout. So a sweep's exception is
 * counted and logged here and the next sweep still runs; and
 * {@code hold_reaper_last_sweep_epoch_seconds} makes a reaper that is stuck for
 * any other reason visible.
 *
 * <h2>Why it is a SmartLifecycle and not started by its factory method</h2>
 *
 * <p>The first version called {@code start()} inside its {@code @Bean} method, so
 * the schedule began while the context was still being built. Spring Boot orders
 * its <em>own</em> JDBC beans after Flyway; a custom bean that merely takes a
 * {@code DataSource} gets no such ordering. On a cold stack the first sweep asked
 * for a connection before Flyway had validated the schema, Hikari was still
 * opening its first connection against a Postgres busy with three starting
 * replicas, and Flyway timed out waiting for the one connection the reaper held:
 * app-2 failed to boot and restarted. Measured: "hold reaper running" at
 * 11:38:01.764, Hikari starting at 11:38:06.205, context failed at 11:38:23.
 *
 * <p>As a {@link SmartLifecycle}, {@link #start()} runs only after every singleton
 * exists and the context has refreshed — Flyway included — and {@link #stop()} runs
 * before the DataSource and Redis client are destroyed, so shutdown does not
 * produce a burst of failed sweeps either.
 */
final class HoldReaper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HoldReaper.class);

    static final String LEASE_KEY = "reaper:lease";

    /** Takes this replica's turn at the sweep, or declines it. */
    @FunctionalInterface
    interface Lease {
        boolean tryAcquire();
    }

    /**
     * {@code SET reaper:lease <owner> NX PX <ttl>}. The owner is written for whoever
     * is reading {@code redis-cli} wondering which replica is reaping, not checked.
     */
    static Lease redisLease(RedisCommands<String, String> redis, String owner, Duration ttl) {
        SetArgs nxPx = SetArgs.Builder.nx().px(ttl.toMillis());
        return () -> "OK".equals(redis.set(LEASE_KEY, owner, nxPx));
    }

    private final Function<Instant, ExpireHolds.Report> sweep;
    private final InstantSource systemClock;
    private final Lease lease;
    private final Duration interval;
    private final boolean autoStartup;

    private final Counter holdsReaped;
    private final Counter bookingsExpired;
    private final Counter completed;
    private final Counter skipped;
    private final Counter failed;
    private final AtomicLong lastSweepEpochSeconds = new AtomicLong();

    private volatile ScheduledExecutorService executor;

    /**
     * @param sweep {@code ExpireHolds::sweep}
     * @param systemClock the application's clock, FR-31 offset included — hold
     *     expiries were stamped with it, so they must be judged by it
     * @param autoStartup {@code tatkalrush.reaper.enabled}; false leaves the bean in
     *     the context, stopped, so §9.2's "correct without a reaper" can be tested
     */
    HoldReaper(
            Function<Instant, ExpireHolds.Report> sweep,
            InstantSource systemClock,
            Lease lease,
            MeterRegistry meters,
            Duration interval,
            boolean autoStartup) {
        this.sweep = sweep;
        this.systemClock = systemClock;
        this.lease = lease;
        this.interval = interval;
        this.autoStartup = autoStartup;

        this.holdsReaped =
                Counter.builder("hold_reaper_holds_reaped_total")
                        .description("§13.2: allocator holds released by the background reaper")
                        .register(meters);
        this.bookingsExpired =
                Counter.builder("hold_reaper_bookings_expired_total")
                        .description("FR-18: bookings moved HELD -> EXPIRED by the reaper")
                        .register(meters);
        this.completed = sweeps(meters, "completed");
        this.skipped = sweeps(meters, "skipped_lease_held");
        this.failed = sweeps(meters, "failed");

        // Wall clock, deliberately NOT systemClock: this is read by Prometheus as
        // time() - value, and Prometheus does not know about FR-31's offset. Read it
        // as max() across replicas - only the lease holder updates it.
        Gauge.builder("hold_reaper_last_sweep_epoch_seconds", lastSweepEpochSeconds, AtomicLong::get)
                .description("When this replica last completed a sweep; max() across replicas")
                .register(meters);
    }

    private static Counter sweeps(MeterRegistry meters, String outcome) {
        return Counter.builder("hold_reaper_sweeps_total")
                .tag("outcome", outcome)
                .description("§13.2 reaper sweeps by outcome")
                .register(meters);
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("hold-reaper").daemon(true).factory());
        executor.scheduleWithFixedDelay(
                this::runOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("hold reaper running every {} (§13.2)", interval);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            // A sweep in flight is allowed to finish its statement rather than be
            // interrupted into a half-read reply; both halves are idempotent, so
            // one cut short is merely repeated by the next replica's sweep.
            executor.awaitTermination(interval.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    /** One tick. Package-private so tests can drive it without waiting. */
    void runOnce() {
        try {
            if (!lease.tryAcquire()) {
                skipped.increment();
                return;
            }

            var report = sweep.apply(systemClock.instant());

            holdsReaped.increment(report.holdsReaped());
            bookingsExpired.increment(report.bookingsExpired());
            completed.increment();
            lastSweepEpochSeconds.set(System.currentTimeMillis() / 1000);

            if (report.backlogRemains()) {
                log.warn(
                        "hold reaper is behind: {} bookings expired this sweep and more remain."
                                + " The next sweep continues; sustained, it means holds lapse faster"
                                + " than they are reaped.",
                        report.bookingsExpired());
            } else if (log.isDebugEnabled()) {
                log.debug(
                        "hold reaper: {} holds reaped, {} bookings expired",
                        report.holdsReaped(),
                        report.bookingsExpired());
            }
        } catch (RuntimeException e) {
            failed.increment();
            log.warn(
                    "hold reaper sweep failed; retrying in {}. No seat is lost while this"
                            + " persists - allocation reaps its own pool (§9.2) - but idle pools"
                            + " keep expired holds and HELD bookings do not expire until it recovers.",
                    interval,
                    e);
        }
    }
}
