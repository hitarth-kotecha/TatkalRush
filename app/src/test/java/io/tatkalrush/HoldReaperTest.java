package io.tatkalrush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tatkalrush.application.usecases.ExpireHolds;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

class HoldReaperTest {

    private static final Instant SYSTEM_NOW = Instant.parse("2026-10-20T04:30:00Z");
    private static final ExpireHolds.Report NOTHING = new ExpireHolds.Report(0, 0, false);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final InstantSource offsetClock = InstantSource.fixed(SYSTEM_NOW);

    private double sweeps(String outcome) {
        return meters.get("hold_reaper_sweeps_total").tag("outcome", outcome).counter().count();
    }

    @Nested
    @DisplayName("one tick")
    class OneTick {

        @Test
        void aSweepRunsOnTheSystemsClockAndIsCounted() {
            var seen = new ArrayList<Instant>();
            var reaper =
                    new HoldReaper(
                            now -> {
                                seen.add(now);
                                return new ExpireHolds.Report(4, 3, false);
                            },
                            offsetClock,
                            () -> true,
                            meters,
                            Duration.ofSeconds(5),
                            true);

            reaper.runOnce();

            // FR-31: hold expiries were stamped on the offset clock, so they are
            // judged by it. Wall-clock here would make every hold look 40 days
            // from expiring - INV-5's bug, one layer further in.
            assertEquals(List.of(SYSTEM_NOW), seen);
            assertEquals(4, meters.get("hold_reaper_holds_reaped_total").counter().count());
            assertEquals(3, meters.get("hold_reaper_bookings_expired_total").counter().count());
            assertEquals(1, sweeps("completed"));
            assertTrue(
                    meters.get("hold_reaper_last_sweep_epoch_seconds").gauge().value() > 0,
                    "a stalled reaper is only visible if a successful one says when it last ran");
        }

        @Test
        void withoutTheLeaseNothingIsSwept() {
            var swept = new AtomicInteger();
            var reaper =
                    new HoldReaper(
                            now -> {
                                swept.incrementAndGet();
                                return NOTHING;
                            },
                            offsetClock,
                            () -> false,
                            meters,
                            Duration.ofSeconds(5),
                            true);

            reaper.runOnce();

            assertEquals(0, swept.get());
            assertEquals(1, sweeps("skipped_lease_held"));
        }

        @Test
        void aFailedSweepIsCountedNotThrown() {
            var reaper =
                    new HoldReaper(
                            now -> {
                                throw new IllegalStateException("redis timed out");
                            },
                            offsetClock,
                            () -> true,
                            meters,
                            Duration.ofSeconds(5),
                            true);

            reaper.runOnce();

            assertEquals(1, sweeps("failed"));
            assertEquals(0, sweeps("completed"));
        }
    }

    /**
     * The reason every failure is caught. scheduleWithFixedDelay cancels a task the
     * first time it throws and tells nobody, so without the catch the reaper would
     * stop for good at the first Redis timeout - silently, and while every health
     * check stayed green.
     */
    @Test
    @DisplayName("a sweep that throws does not stop the sweeps after it")
    void theScheduleSurvivesAFailure() throws InterruptedException {
        var calls = new AtomicInteger();
        var thirdSweep = new CountDownLatch(1);
        var reaper =
                new HoldReaper(
                        now -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new IllegalStateException("first sweep fails");
                            }
                            if (calls.get() >= 3) {
                                thirdSweep.countDown();
                            }
                            return NOTHING;
                        },
                        offsetClock,
                        () -> true,
                        meters,
                        Duration.ofMillis(20),
                        true);
        try {
            reaper.start();
            assertTrue(
                    thirdSweep.await(5, TimeUnit.SECONDS),
                    "the reaper stopped after its first failure; calls=" + calls.get());
        } finally {
            reaper.stop();
        }
    }

    @Test
    @DisplayName("nothing sweeps until the lifecycle starts it, and nothing after it stops")
    void theLifecycleOwnsTheSchedule() throws InterruptedException {
        var calls = new AtomicInteger();
        var reaper =
                new HoldReaper(
                        now -> {
                            calls.incrementAndGet();
                            return NOTHING;
                        },
                        offsetClock,
                        () -> true,
                        meters,
                        Duration.ofMillis(20),
                        true);

        Thread.sleep(150);
        assertFalse(reaper.isRunning());
        assertEquals(0, calls.get(), "constructing the bean must not start the schedule");

        reaper.start();
        assertTrue(reaper.isRunning());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (calls.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(calls.get() > 0, "started, but never swept");

        reaper.stop();
        assertFalse(reaper.isRunning());
        int atStop = calls.get();
        Thread.sleep(150);
        assertEquals(atStop, calls.get(), "swept after stop(): shutdown would race the DataSource");
    }

    @Test
    @DisplayName("the Redis lease admits one replica per turn, and lapses on its own")
    void theRedisLeaseIsExclusiveAndExpires() throws InterruptedException {
        try (GenericContainer<?> redisContainer =
                new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            redisContainer.start();
            var client =
                    RedisClient.create(
                            RedisURI.create(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
            try (var connection = client.connect()) {
                var redis = connection.sync();
                var ttl = Duration.ofMillis(300);
                var app1 = HoldReaper.redisLease(redis, "app-1", ttl);
                var app2 = HoldReaper.redisLease(redis, "app-2", ttl);

                assertTrue(app1.tryAcquire());
                assertFalse(app2.tryAcquire(), "two replicas must not sweep the same turn");
                assertFalse(app1.tryAcquire(), "not even the holder, until its turn is over");
                assertEquals("app-1", redis.get(HoldReaper.LEASE_KEY));

                // A replica that dies holding the lease costs one sweep, not the reaper.
                Thread.sleep(ttl.toMillis() + 200);
                assertTrue(app2.tryAcquire(), "the lease must lapse without its holder");
            } finally {
                client.shutdown();
            }
        }
    }
}
