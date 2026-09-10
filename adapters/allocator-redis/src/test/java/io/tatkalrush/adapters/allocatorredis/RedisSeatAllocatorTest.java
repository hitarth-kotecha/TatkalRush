package io.tatkalrush.adapters.allocatorredis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.tatkalrush.application.ports.SeatAllocator;
import io.tatkalrush.application.ports.SeatAllocatorContract;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.TravelClass;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * <b>AC-1.6</b>: Strategy A against the allocator contract suite.
 *
 * <p>Not one test is defined here. Every case comes from
 * {@link SeatAllocatorContract}, inherited through {@code application}'s test-jar
 * — the same compiled class Strategy B will extend in Phase 2, where AC-2.1
 * requires it to pass with no test modifications. This file supplies a subject
 * and a way to provision a pool, and nothing else.
 *
 * <p>That includes <b>T-1</b>: 500 virtual threads released simultaneously
 * against a one-berth pool, exactly one of which may win. Strategy A's answer to
 * that is not a lock in this codebase — it is that Redis executes Lua
 * single-threaded, so the read-modify-write cannot interleave. The contract does
 * not care which mechanism; it cares that the outcome is the same.
 */
class RedisSeatAllocatorTest extends SeatAllocatorContract {

    // The image compose.yaml pins. A different Redis is a different Lua, and the
    // whole of FR-3a depends on which Lua this is.
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisSeatAllocator allocator;

    private final AtomicLong nextScheduleId = new AtomicLong(1);

    @BeforeAll
    static void start() {
        REDIS.start();
        client =
                RedisClient.create(
                        "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect();
        allocator = new RedisSeatAllocator(connection.sync());
    }

    @AfterAll
    static void stop() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        REDIS.stop();
    }

    @Override
    protected SeatAllocator allocator() {
        return allocator;
    }

    @Override
    protected PoolKey givenPool(int berthCount, int segmentCount) {
        // A fresh schedule id per pool, so no test can reach another's state
        // through a shared Redis key.
        var key =
                new PoolKey(nextScheduleId.getAndIncrement(), TravelClass.SL, QuotaType.TATKAL);
        allocator.provision(key, berthCount, segmentCount, berthIds(key, berthCount), List.of());
        return key;
    }

    /**
     * Berth ids that are deliberately awkward.
     *
     * <p>This used to be {@code scheduleId * 1000 + ordinal} — the exact formula
     * the allocator itself used, so the fixture and the code under test agreed with
     * each other and both disagreed with {@code pool_berths}. Every test passed
     * while production stored the wrong berth.
     *
     * <p>So the mapping here is <b>descending</b> and offset: ordinal order is not
     * id order, ids are not contiguous, and nothing about an id can be recovered
     * from an ordinal by arithmetic anyone would guess. Any attempt to derive one
     * from the other fails here rather than in a benchmark.
     */
    private static List<Long> berthIds(PoolKey pool, int berthCount) {
        var ids = new ArrayList<Long>(berthCount);
        for (int ordinal = 0; ordinal < berthCount; ordinal++) {
            // 64, not berthCount: the id for a given ordinal must not depend on how
            // large the pool is, or berthIdsOf(pool) below would disagree with what
            // givenPool actually provisioned.
            ids.add(pool.scheduleId() * 1_000_000L + (64 - ordinal) * 3L + 1L);
        }
        return List.copyOf(ids);
    }

    @Override
    protected List<Long> berthIdsOf(PoolKey pool) {
        // The contract asks for enough ids to cover any pool it built; 64 is the
        // widest a mask can address.
        return berthIds(pool, 64);
    }
}
