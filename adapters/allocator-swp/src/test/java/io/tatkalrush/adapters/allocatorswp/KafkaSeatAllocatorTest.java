package io.tatkalrush.adapters.allocatorswp;

import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.application.ports.SeatAllocatorContract;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * <b>AC-1.6</b>: Strategy B, through milestone 2, against the allocator contract
 * suite.
 *
 * <p>Same suite {@link RedisSeatAllocatorTest} (Strategy A) extends, unmodified —
 * AC-2.1 requires that. Includes T-1: 500 virtual threads racing for the last
 * berth. Strategy A's answer is "Redis runs Lua single-threaded"; this class's
 * answer is "Kafka guarantees one consumer per partition", and the contract does
 * not care which — only that exactly one thread ever wins.
 *
 * <p>Four command partitions, not §8.3's twelve: this suite provisions a handful
 * of pools per test, not thousands, and a smaller topic keeps the container's
 * startup cheap. T-1 itself allocates from a single pool, so it exercises exactly
 * one partition regardless of how many exist.
 *
 * <p>Milestone 2's fencing mechanism ({@code ProducerFencingTest}) is tested
 * separately, at the level of the Kafka transactional-producer API it depends on
 * — orchestrating a real consumer-group rebalance between two live
 * {@code KafkaSeatAllocator} instances deterministically is a chaos-suite concern
 * (T-C7), not a contract-suite one.
 */
class KafkaSeatAllocatorTest extends SeatAllocatorContract {

    private static final int COMMAND_PARTITIONS = 4;

    // Same image compose.yaml pins (§8.3) - see RedisSeatAllocatorTest for why
    // this project hand-rolls containers rather than trust an unpinned default.
    private static final KafkaContainer KAFKA =
            new KafkaContainer(
                    DockerImageName.parse(
                            "apache/kafka@sha256:d50ab7b5df612b3c303f9d8afe8fee59626a5de798addfd626fe1924e3205965"));

    private static KafkaSeatAllocator allocator;

    private final AtomicLong nextScheduleId = new AtomicLong(1);

    @BeforeAll
    static void start() throws Exception {
        KAFKA.start();

        var adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(
                            List.of(
                                    new NewTopic("booking-commands", COMMAND_PARTITIONS, (short) 1),
                                    new NewTopic("booking-events", COMMAND_PARTITIONS, (short) 1),
                                    new NewTopic("booking-replies", 1, (short) 1)))
                    .all()
                    .get();
        }

        allocator =
                new KafkaSeatAllocator(
                        KAFKA.getBootstrapServers(),
                        "booking-commands",
                        "booking-events",
                        "booking-replies",
                        Duration.ofSeconds(30),
                        new InMemoryCheckpointStore(),
                        new InMemoryHoldRoutingStore());
        allocator.start();
    }

    @AfterAll
    static void stop() {
        if (allocator != null) {
            allocator.close();
        }
        KAFKA.stop();
    }

    /**
     * Milestone 4's dedup cache (§9.3, DD-009) is keyed by bare commandId, not
     * by pool — a real commandId does not carry one to key by. This suite's
     * distinct-schedule-id-per-pool isolation therefore does not extend to it:
     * many tests reuse literal holdIds like {@code "h"} and {@code "a"}, and
     * without this reset, a later test's {@code "h"} can hit an earlier test's
     * cached reply for a different pool within the (production-sized) TTL
     * window. See {@code KafkaSeatAllocator.clearDedupCachesForTesting} for why
     * shrinking that TTL instead would be the wrong fix.
     */
    @BeforeEach
    void clearDedupCaches() {
        allocator.clearDedupCachesForTesting();
    }

    @Override
    protected SeatAllocator allocator() {
        return allocator;
    }

    @Override
    protected PoolKey givenPool(int berthCount, int segmentCount) {
        var pool = new PoolKey(nextScheduleId.getAndIncrement(), TravelClass.SL, QuotaType.GENERAL);
        List<Long> berthIds = berthIdsOf(pool).subList(0, berthCount);
        allocator.provision(pool, berthCount, segmentCount, berthIds);
        return pool;
    }

    /**
     * Deterministic from the pool alone, exactly like {@code RedisSeatAllocatorTest}'s
     * scheme - milestone 1 has no persistence layer to be the source of truth for
     * real {@code berths.id} values, so this stands in for one. Sized well above
     * any pool this suite provisions; {@link #givenPool} takes the prefix it needs.
     */
    @Override
    protected List<Long> berthIdsOf(PoolKey pool) {
        var ids = new java.util.ArrayList<Long>(64);
        for (int i = 0; i < 64; i++) {
            ids.add(pool.scheduleId() * 1000 + i);
        }
        return ids;
    }
}
