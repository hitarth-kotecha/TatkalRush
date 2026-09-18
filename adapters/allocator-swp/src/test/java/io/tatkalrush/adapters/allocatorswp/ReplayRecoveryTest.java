package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
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
 * <b>The point of milestone 3</b>: a partition's owner dies, a new one takes it
 * over, and the pool's state comes back exactly as it was — without the second
 * allocator instance ever being told anything the first one knew.
 *
 * <p>One partition only, deliberately: this test needs the SAME Kafka partition
 * to move from the first allocator to the second, and controlling that through a
 * real rebalance is only deterministic when there is nowhere else for it to go.
 * Both allocators join the same (hardcoded) consumer group, so closing the first
 * — a clean {@code LeaveGroup}, not a crash, but {@code onPartitionsRevoked} and
 * {@code onPartitionsLost} discard identically (§9.3, DD-007) — hands the
 * partition to the second, which must replay {@code booking-events} before it
 * will answer anything.
 */
class ReplayRecoveryTest {

    private static final String COMMANDS = "replay-commands";
    private static final String EVENTS = "replay-events";
    private static final String REPLIES = "replay-replies";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse(
                            "apache/kafka@sha256:d50ab7b5df612b3c303f9d8afe8fee59626a5de798addfd626fe1924e3205965"));

    @BeforeAll
    static void start() throws Exception {
        KAFKA.start();
        var adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(COMMANDS, 1, (short) 1),
                                    new NewTopic(EVENTS, 1, (short) 1),
                                    new NewTopic(REPLIES, 1, (short) 1)))
                    .all()
                    .get();
        }
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    private KafkaSeatAllocator newAllocator() {
        var allocator =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(), COMMANDS, EVENTS, REPLIES, Duration.ofSeconds(30),
                        new InMemoryCheckpointStore(), new InMemoryHoldRoutingStore());
        allocator.start();
        return allocator;
    }

    @Test
    @DisplayName("a second owner recovers a pool's exact availability after the first dies, via replay alone")
    void recoversStateAfterOwnerDeathAndReplay() {
        var pool = new PoolKey(1, TravelClass.SL, QuotaType.GENERAL);
        var berthIds = List.of(1000L, 1001L, 1002L, 1003L);
        var now = Instant.now();

        int freeBeforeDeath;
        KafkaSeatAllocator first = newAllocator();
        try {
            first.provision(pool, 4, 4, berthIds);

            var a =
                    assertInstanceOf(
                            AllocationResult.Allocated.class,
                            first.allocate(new AllocationRequest(pool, SegmentRange.of(0, 2), 1, "h1", now, 120_000)));
            var b =
                    assertInstanceOf(
                            AllocationResult.Allocated.class,
                            first.allocate(
                                    new AllocationRequest(pool, SegmentRange.of(2, 4), 2, "h2", now, 120_000)));

            // A release, so replay has to prove it undoes an allocation too, not
            // just replays one forward.
            first.release("h2");

            // h1 (1 passenger) takes berth 0 on [0,2). h2 (2 passengers) then
            // takes berths 0 and 1 on [2,4) - berth 0 qualifies because [0,2)
            // and [2,4) do not overlap. Releasing h2 frees berth 1 entirely and
            // frees berth 0's [2,4) half; only berth 0's [0,2) half (h1) stays
            // occupied. freeOn is the MINIMUM across segments, and segments 0-1
            // (where only berth 0 is taken) set that minimum to 3, not a simple
            // "4 berths minus 1 hold".
            freeBeforeDeath = first.availability(pool, SegmentRange.of(0, 4)).freeBerths();
            assertEquals(3, freeBeforeDeath, "only berth 0's [0,2) half (h1) remains occupied");
        } finally {
            first.close(); // clean LeaveGroup, but onPartitionsRevoked discards all the same
        }

        KafkaSeatAllocator second = newAllocator();
        try {
            // No checkpoint was consulted (milestone 3's stated scope cut) and no
            // state crossed in memory - this call can only succeed if replaying
            // booking-events on the second instance reconstructed the pool.
            int freeAfterRecovery = second.availability(pool, SegmentRange.of(0, 4)).freeBerths();
            assertEquals(freeBeforeDeath, freeAfterRecovery);
        } finally {
            second.close();
        }
    }
}
