package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.application.ports.ConfirmResult;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DD-049's gap, closed: a new owner restores from checkpoints and replays only the
 * tail, instead of the whole topic.
 *
 * <p>One partition, as in {@link ReplayRecoveryTest}: the point is the SAME
 * partition moving from one owner to the next, and a real rebalance is only
 * deterministic when there is nowhere else for it to go. Unlike that test, the
 * allocators here SHARE a checkpoint store - the first owner's snapshots are
 * exactly what the second recovers from. The dedup window is shortened so that
 * "recent enough to need replaying for dedup" does not swallow the whole short
 * test; production's 60 s is not under test here.
 *
 * <p>Each test uses its own topics, so one test's log never becomes another's
 * recovery.
 */
class CheckpointReplayTest {

    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse(
                            "apache/kafka@sha256:d50ab7b5df612b3c303f9d8afe8fee59626a5de798addfd626fe1924e3205965"));

    private static final Duration SHORT_DEDUP = Duration.ofSeconds(1);

    @BeforeAll
    static void start() {
        KAFKA.start();
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    private record Topics(String commands, String events, String replies) {}

    private static Topics topics(String name) throws Exception {
        var t = new Topics(name + "-commands", name + "-events", name + "-replies");
        var props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(t.commands(), 1, (short) 1),
                                    new NewTopic(t.events(), 1, (short) 1),
                                    new NewTopic(t.replies(), 1, (short) 1)))
                    .all()
                    .get();
        }
        return t;
    }

    private static KafkaSeatAllocator allocator(Topics t, CheckpointStore store) {
        return allocator(t, store, new InMemoryHoldRoutingStore());
    }

    /**
     * The routing store is the durable {@code holdId -> pool} directory a real
     * deployment keeps in Postgres. Recovery restores a hold, but a confirm
     * arriving at the NEW owner still has to find which pool it belongs to.
     */
    private static KafkaSeatAllocator allocator(Topics t, CheckpointStore store, HoldRoutingStore routing) {
        var allocator =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(),
                        t.commands(),
                        t.events(),
                        t.replies(),
                        Duration.ofSeconds(30),
                        store,
                        routing,
                        new SimpleMeterRegistry());
        allocator.setDedupTtlForTesting(SHORT_DEDUP);
        allocator.start();
        return allocator;
    }

    private static AllocationResult.Allocated hold(
            KafkaSeatAllocator a, PoolKey pool, int from, int to, int passengers, String holdId) {
        return assertInstanceOf(
                AllocationResult.Allocated.class,
                a.allocate(new AllocationRequest(pool, SegmentRange.of(from, to), passengers, holdId, Instant.now(), 600_000)));
    }

    /** Recovery runs on the consumer thread after the rebalance, not inside start(). */
    private static KafkaSeatAllocator.ReplayStats recovery(KafkaSeatAllocator a) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (a.lastReplayStats(0) == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the second owner never finished recovering partition 0");
            }
            Thread.sleep(50);
        }
        return a.lastReplayStats(0);
    }

    private static int free(KafkaSeatAllocator a, PoolKey pool) {
        return a.availability(pool, SegmentRange.of(0, 4)).freeBerths();
    }

    @Test
    @DisplayName("recovery restores from checkpoints and replays only the tail, to exactly the same state")
    void recoversFromCheckpointsAndReplaysOnlyTheTail() throws Exception {
        var t = topics("tail");
        var store = new InMemoryCheckpointStore();
        var routing = new InMemoryHoldRoutingStore();
        var busy = new PoolKey(1, TravelClass.SL, QuotaType.GENERAL);
        var idle = new PoolKey(2, TravelClass.SL, QuotaType.GENERAL);

        int freeBefore;
        int freeIdleBefore;
        var first = allocator(t, store, routing);
        try {
            first.provision(busy, 4, 4, List.of(1000L, 1001L, 1002L, 1003L));
            first.provision(idle, 4, 4, List.of(2000L, 2001L, 2002L, 2003L));

            // Before the checkpoint: churn that a full replay would have to apply, and
            // two holds that must survive INSIDE the snapshot rather than by replay.
            hold(first, busy, 0, 2, 1, "kept-1");
            hold(first, busy, 2, 4, 1, "kept-2");
            for (int i = 0; i < 15; i++) {
                hold(first, busy, 0, 4, 1, "churn-" + i);
                first.release("churn-" + i);
            }
            first.checkpointNow();

            // After the checkpoint: the tail recovery must replay on top of it.
            hold(first, busy, 0, 4, 1, "tail-hold");
            first.release("kept-2");

            freeBefore = free(first, busy);
            freeIdleBefore = free(first, idle);
        } finally {
            first.close();
        }
        Thread.sleep(SHORT_DEDUP.toMillis() + 1_500); // everything is now outside the dedup window

        var second = allocator(t, store, routing);
        try {
            var stats = recovery(second);
            assertTrue(stats.fromCheckpoints(), "the checkpoints were usable, so they must have been used");
            assertTrue(stats.startOffset() > 0, "recovery must not have started from offset 0, was " + stats.startOffset());

            assertEquals(freeBefore, free(second, busy), "the busy pool's state is identical after recovery");
            assertEquals(freeIdleBefore, free(second, idle), "so is the idle pool's");
            assertInstanceOf(
                    ConfirmResult.Confirmed.class,
                    second.confirm("kept-1", 77L),
                    "a hold taken before the checkpoint survived inside the snapshot");
            assertInstanceOf(
                    ConfirmResult.Confirmed.class,
                    second.confirm("tail-hold", 78L),
                    "a hold taken after the checkpoint arrived by replaying the tail");
            assertInstanceOf(
                    ConfirmResult.HoldExpired.class,
                    second.confirm("kept-2", 79L),
                    "a release after the checkpoint was replayed too");
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("an idle pool's checkpoint keeps pace with the partition, so it cannot pin recovery in the past")
    void anIdlePoolDoesNotPinTheFloor() throws Exception {
        var t = topics("idle");
        var store = new InMemoryCheckpointStore();
        var busy = new PoolKey(1, TravelClass.SL, QuotaType.GENERAL);
        var idle = new PoolKey(2, TravelClass.SL, QuotaType.GENERAL);

        var first = allocator(t, store);
        try {
            first.provision(busy, 4, 4, List.of(1000L, 1001L, 1002L, 1003L));
            first.provision(idle, 4, 4, List.of(2000L, 2001L, 2002L, 2003L));
            first.checkpointNow();
            long idleAtProvisioning = store.load(idle.keySuffix()).orElseThrow().kafkaOffset();

            for (int i = 0; i < 10; i++) {
                hold(first, busy, 0, 4, 1, "h-" + i);
                first.release("h-" + i);
            }
            first.checkpointNow();

            long busyOffset = store.load(busy.keySuffix()).orElseThrow().kafkaOffset();
            long idleOffset = store.load(idle.keySuffix()).orElseThrow().kafkaOffset();
            assertTrue(
                    idleOffset > idleAtProvisioning,
                    "the idle pool's checkpoint moved forward although nothing touched it");
            assertEquals(busyOffset, idleOffset, "both are complete through the same point of the partition");
        } finally {
            first.close();
        }
    }

    @Test
    @DisplayName("a presence marker in the partition forces a full replay, and recovery is still exact")
    void aMarkerForcesAFullReplay() throws Exception {
        var t = topics("marker");
        var store = new InMemoryCheckpointStore();
        var pool = new PoolKey(1, TravelClass.SL, QuotaType.GENERAL);

        int freeBefore;
        var first = allocator(t, store);
        try {
            first.provision(pool, 4, 4, List.of(1000L, 1001L, 1002L, 1003L));
            hold(first, pool, 0, 2, 1, "h1");
            first.checkpointNow();
            freeBefore = free(first, pool);
        } finally {
            first.close();
        }
        // The state a pool is in between provisioning and its first real snapshot.
        store.save(pool.keySuffix(), 0, -1, Long.MAX_VALUE, new byte[] {0});
        Thread.sleep(SHORT_DEDUP.toMillis() + 1_500);

        var second = allocator(t, store);
        try {
            var stats = recovery(second);
            assertFalse(stats.fromCheckpoints());
            assertEquals(0, stats.startOffset(), "no usable checkpoint set, so everything is replayed");
            assertEquals(freeBefore, free(second, pool));
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("a snapshot that cannot be decoded falls back to a full replay instead of failing the partition")
    void anUndecodableSnapshotFallsBack() throws Exception {
        var t = topics("garbage");
        var store = new InMemoryCheckpointStore();
        var pool = new PoolKey(1, TravelClass.SL, QuotaType.GENERAL);

        int freeBefore;
        var first = allocator(t, store);
        try {
            first.provision(pool, 4, 4, List.of(1000L, 1001L, 1002L, 1003L));
            hold(first, pool, 0, 2, 1, "h1");
            first.checkpointNow();
            freeBefore = free(first, pool);
        } finally {
            first.close();
        }
        var good = store.load(pool.keySuffix()).orElseThrow();
        store.save(pool.keySuffix(), 0, good.kafkaOffset(), Long.MAX_VALUE, new byte[] {1, 2, 3}); // an old format
        Thread.sleep(SHORT_DEDUP.toMillis() + 1_500);

        var second = allocator(t, store);
        try {
            assertFalse(recovery(second).fromCheckpoints());
            assertEquals(freeBefore, free(second, pool));
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("a checkpoint claiming an offset beyond the log's end is not trusted")
    void anOffsetPastTheLogIsNotTrusted() throws Exception {
        var t = topics("beyond");
        var store = new InMemoryCheckpointStore();
        var pool = new PoolKey(1, TravelClass.SL, QuotaType.GENERAL);

        int freeBefore;
        var first = allocator(t, store);
        try {
            first.provision(pool, 4, 4, List.of(1000L, 1001L, 1002L, 1003L));
            hold(first, pool, 0, 2, 1, "h1");
            first.checkpointNow();
            freeBefore = free(first, pool);
        } finally {
            first.close();
        }
        var good = store.load(pool.keySuffix()).orElseThrow();
        // A snapshot from some other life of this topic: a skip past the end would
        // drop every event, and recovery would silently come up wrong.
        store.save(pool.keySuffix(), 0, 1_000_000L, Long.MAX_VALUE, good.maskSnapshot());
        Thread.sleep(SHORT_DEDUP.toMillis() + 1_500);

        var second = allocator(t, store);
        try {
            assertFalse(recovery(second).fromCheckpoints());
            assertEquals(freeBefore, free(second, pool));
        } finally {
            second.close();
        }
    }
}
