package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * <b>The scenario milestone 5 exists for</b>: {@code CancelBooking.release()}
 * and {@code ExpireHolds}'s sweep run on whichever replica happens to pick up
 * that request — not necessarily the one that ran {@code HoldSeats}. This test
 * is that scenario directly: two independent {@code KafkaSeatAllocator}
 * instances, joined to the <em>same</em> Kafka consumer group (exactly what two
 * replicas of this application would do) and sharing the durable
 * {@code hold_routing} directory (a real deployment's shared Postgres) but
 * nothing else in memory. One allocates; the other releases and confirms holds
 * it never created.
 *
 * <p>Two command partitions, so the two instances are likely to each own at
 * least one — not load-bearing for correctness (a single partition would still
 * prove the routing), but closer to what a real two-replica deployment looks
 * like.
 */
class CrossReplicaHoldRoutingTest {

    private static final String COMMANDS = "cross-replica-commands";
    private static final String EVENTS = "cross-replica-events";
    private static final String REPLIES = "cross-replica-replies";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse(
                            "apache/kafka@sha256:d50ab7b5df612b3c303f9d8afe8fee59626a5de798addfd626fe1924e3205965"));

    private KafkaSeatAllocator replicaA;
    private KafkaSeatAllocator replicaB;

    @BeforeAll
    static void start() throws Exception {
        KAFKA.start();
        var adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(COMMANDS, 2, (short) 1),
                                    new NewTopic(EVENTS, 2, (short) 1),
                                    new NewTopic(REPLIES, 1, (short) 1)))
                    .all()
                    .get();
        }
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    @AfterEach
    void closeReplicas() {
        if (replicaA != null) {
            replicaA.close();
        }
        if (replicaB != null) {
            replicaB.close();
        }
    }

    @Test
    @DisplayName("replica B releases a HELD hold that replica A created, having never seen it before")
    void replicaBReleasesAHoldReplicaACreated() {
        var sharedRouting = new InMemoryHoldRoutingStore(); // stands in for shared Postgres
        replicaA =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(), COMMANDS, EVENTS, REPLIES, Duration.ofSeconds(30),
                        new InMemoryCheckpointStore(), sharedRouting);
        replicaA.start();
        replicaB =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(), COMMANDS, EVENTS, REPLIES, Duration.ofSeconds(30),
                        new InMemoryCheckpointStore(), sharedRouting);
        replicaB.start();

        var pool = new PoolKey(1, TravelClass.SL, QuotaType.GENERAL);
        replicaA.provision(pool, 2, 4, List.of(3000L, 3001L));

        var request =
                new AllocationRequest(pool, SegmentRange.of(0, 4), 1, "held-by-a", Instant.now(), 120_000);
        assertInstanceOf(AllocationResult.Allocated.class, replicaA.allocate(request));

        // Replica B's own holdRouting map has never heard of "held-by-a" - this
        // can only succeed via the shared hold_routing fallback.
        replicaB.release("held-by-a");

        assertInstanceOf(
                AllocationResult.Allocated.class,
                replicaA.allocate(
                        new AllocationRequest(
                                pool, SegmentRange.of(0, 4), 2, "after-release", Instant.now(), 120_000)),
                "both berths must be free again - the release from replica B actually reached the owner");
    }

    @Test
    @DisplayName("replica B confirms a HELD hold that replica A created, having never seen it before")
    void replicaBConfirmsAHoldReplicaACreated() {
        var sharedRouting = new InMemoryHoldRoutingStore();
        replicaA =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(), COMMANDS, EVENTS, REPLIES, Duration.ofSeconds(30),
                        new InMemoryCheckpointStore(), sharedRouting);
        replicaA.start();
        replicaB =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(), COMMANDS, EVENTS, REPLIES, Duration.ofSeconds(30),
                        new InMemoryCheckpointStore(), sharedRouting);
        replicaB.start();

        var pool = new PoolKey(2, TravelClass.SL, QuotaType.GENERAL);
        replicaA.provision(pool, 1, 4, List.of(3100L));

        var request =
                new AllocationRequest(pool, SegmentRange.of(0, 4), 1, "held-by-a-2", Instant.now(), 120_000);
        assertInstanceOf(AllocationResult.Allocated.class, replicaA.allocate(request));

        assertInstanceOf(
                ConfirmResult.Confirmed.class, replicaB.confirm("held-by-a-2", 77L), "resolved via the shared directory");
    }
}
