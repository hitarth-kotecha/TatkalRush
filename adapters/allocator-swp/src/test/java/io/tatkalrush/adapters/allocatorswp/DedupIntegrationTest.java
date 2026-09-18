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
 * <b>The scenario milestone 4 exists for</b> (§9.3, DD-009): the owner
 * completes an {@code allocate} and commits, but the reply never made it back —
 * and whatever called {@link KafkaSeatAllocator#allocate} retries with the
 * <em>same</em> {@code holdId}. Without a dedup cache, the domain's own guard
 * against a duplicate hold turns that into an error (see
 * {@code BerthPool.allocate}'s "hold already exists"); with it, the retry gets
 * back exactly what the first attempt got.
 *
 * <p>One partition, deliberately: correctness here does not depend on it, but a
 * single pool's own commandId reuse is the entire point of this test, and a
 * dedicated small topic keeps it independent of the shared contract suite's
 * container and its dedup-cache reset requirement (see
 * {@code KafkaSeatAllocatorTest.clearDedupCaches}).
 */
class DedupIntegrationTest {

    private static final String COMMANDS = "dedup-commands";
    private static final String EVENTS = "dedup-events";
    private static final String REPLIES = "dedup-replies";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse(
                            "apache/kafka@sha256:d50ab7b5df612b3c303f9d8afe8fee59626a5de798addfd626fe1924e3205965"));

    private static KafkaSeatAllocator allocator;

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
        allocator =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(), COMMANDS, EVENTS, REPLIES, Duration.ofSeconds(30),
                        new InMemoryCheckpointStore(), new InMemoryHoldRoutingStore());
        allocator.start();
    }

    @AfterAll
    static void stop() {
        if (allocator != null) {
            allocator.close();
        }
        KAFKA.stop();
    }

    @Test
    @DisplayName("retrying the same Allocate command returns the original berths, not an error or a second hold")
    void retriedAllocateReturnsOriginalReply() {
        var pool = new PoolKey(1, TravelClass.SL, QuotaType.GENERAL);
        allocator.provision(pool, 2, 4, List.of(2000L, 2001L));

        var request = new AllocationRequest(pool, SegmentRange.of(0, 4), 1, "retry-me", Instant.now(), 120_000);

        var first = assertInstanceOf(AllocationResult.Allocated.class, allocator.allocate(request));
        var retry = assertInstanceOf(AllocationResult.Allocated.class, allocator.allocate(request));

        assertEquals(first.berthIds(), retry.berthIds(), "the retry must get the SAME berths, not a fresh scan");
        assertEquals(first.expiresAt(), retry.expiresAt());

        // Only one berth actually left the pool - a second, different berth was
        // never allocated under the same holdId.
        assertEquals(1, allocator.availability(pool, SegmentRange.of(0, 4)).freeBerths());
    }

    @Test
    @DisplayName("a genuinely new request with a different holdId is never affected by an unrelated dedup entry")
    void differentHoldIdIsNotDeduped() {
        var pool = new PoolKey(2, TravelClass.SL, QuotaType.GENERAL);
        allocator.provision(pool, 2, 4, List.of(2100L, 2101L));

        var a = new AllocationRequest(pool, SegmentRange.of(0, 4), 1, "first", Instant.now(), 120_000);
        var b = new AllocationRequest(pool, SegmentRange.of(0, 4), 1, "second", Instant.now(), 120_000);

        var resultA = assertInstanceOf(AllocationResult.Allocated.class, allocator.allocate(a));
        var resultB = assertInstanceOf(AllocationResult.Allocated.class, allocator.allocate(b));

        assertEquals(List.of(2100L), resultA.berthIds());
        assertEquals(List.of(2101L), resultB.berthIds(), "a different holdId must be treated as a new command");
    }
}
