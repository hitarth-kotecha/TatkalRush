package io.tatkalrush.adapters.allocatorredis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.tatkalrush.application.ports.AllocationRequest;
import io.tatkalrush.application.ports.AllocationResult;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.QuotaType;
import io.tatkalrush.domain.inventory.SegmentRange;
import io.tatkalrush.domain.inventory.TravelClass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * The ordinal to {@code berths.id} mapping.
 *
 * <h2>What this exists to prevent happening again</h2>
 *
 * <p>The allocator used to derive berth ids as {@code scheduleId * 1000 + ordinal}.
 * Real ids come from {@code pool_berths}, and the difference was invisible to the
 * entire suite because {@code InMemorySeatAllocator}, the contract's fixture and
 * T-7's comparison all reproduced the same formula. Four components agreed with
 * each other and none of them agreed with the database.
 *
 * <p>Running it against seeded data showed what that costs. Where the fabricated
 * id happened to fall inside {@code berths.id} the foreign key accepted it and the
 * booking recorded <b>the wrong berth</b> — pool 5's ordinal 7 is berth 8, and the
 * arithmetic stored 5007, an id not in that pool at all. Above
 * {@code max(berths.id)} the hold returned 500.
 *
 * <p>So every id here is deliberately <b>descending and non-contiguous</b>: ordinal
 * order is not id order, and nothing about an id is recoverable from an ordinal by
 * arithmetic anyone would guess.
 */
class BerthIdMappingTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
    private static final long TTL = 120_000L;

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> redis;
    private static RedisSeatAllocator allocator;

    private static final AtomicLong NEXT_SCHEDULE = new AtomicLong(1);

    @BeforeAll
    static void start() {
        REDIS.start();
        client = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect();
        redis = connection.sync();
        allocator = new RedisSeatAllocator(redis);
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

    @Test
    @DisplayName("allocate returns the ids it was provisioned with, not derived ones")
    void allocateReturnsTheProvisionedIds() {
        PoolKey pool = pool();
        List<Long> ids = arbitraryIds(pool, 4);
        allocator.provision(pool, 4, 4, ids, List.of());

        var allocated =
                (AllocationResult.Allocated)
                        allocator.allocate(
                                new AllocationRequest(
                                        pool, new SegmentRange(0, 4), 2, "h1", NOW, TTL));

        // FR-5 takes the lowest ORDINALS, which here are the two highest ids. Both
        // halves matter: the right berths, named correctly.
        assertEquals(List.of(ids.get(0), ids.get(1)), allocated.berthIds());
    }

    @Test
    void theIdsAreNotDerivableFromTheScheduleAndOrdinal() {
        PoolKey pool = pool();
        List<Long> ids = arbitraryIds(pool, 4);
        allocator.provision(pool, 4, 4, ids, List.of());

        var allocated =
                (AllocationResult.Allocated)
                        allocator.allocate(
                                new AllocationRequest(
                                        pool, new SegmentRange(0, 2), 1, "h2", NOW, TTL));

        long derived = pool.scheduleId() * 1000L;
        assertTrue(
                allocated.berthIds().get(0) != derived,
                "ordinal 0 came back as scheduleId * 1000 - the arithmetic is back");
    }

    @Test
    @DisplayName("a second allocator reads the mapping back from Redis")
    void theMappingSurvivesAColdJvm() {
        PoolKey pool = pool();
        List<Long> ids = arbitraryIds(pool, 3);
        allocator.provision(pool, 3, 4, ids, List.of());

        // Any replica may serve a request for a pool it never provisioned. A
        // mapping that lived only in the provisioning JVM's cache would make the
        // berths a booking names depend on which replica took the call.
        var otherReplica = new RedisSeatAllocator(redis);
        var allocated =
                (AllocationResult.Allocated)
                        otherReplica.allocate(
                                new AllocationRequest(
                                        pool, new SegmentRange(0, 4), 1, "h3", NOW, TTL));

        assertEquals(List.of(ids.get(0)), allocated.berthIds());
    }

    @Test
    void confirmReturnsTheSameIdsAsTheHold() {
        PoolKey pool = pool();
        List<Long> ids = arbitraryIds(pool, 3);
        allocator.provision(pool, 3, 4, ids, List.of());

        var held =
                (AllocationResult.Allocated)
                        allocator.allocate(
                                new AllocationRequest(
                                        pool, new SegmentRange(0, 4), 2, "h4", NOW, TTL));
        var confirmed = allocator.confirm("h4", 99L);

        // ConfirmBooking writes these into seat_allocations. If confirm and hold
        // disagreed, the durable record would name different berths from the ones
        // the mask says are taken - and INV-8 would fire on the next check.
        assertEquals(
                held.berthIds(),
                ((io.tatkalrush.application.ports.ConfirmResult.Confirmed) confirmed).berthIds());
    }

    @Test
    void releasingABerthFromAnotherPoolIsRefused() {
        PoolKey pool = pool();
        allocator.provision(pool, 3, 4, arbitraryIds(pool, 3), List.of());

        // Refusing beats clearing whatever bit an arithmetic guess lands on, which
        // would free a berth belonging to a booking nobody cancelled.
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                allocator.releaseConfirmed(
                                        pool, new SegmentRange(0, 4), List.of(424_242L)));
        assertTrue(e.getMessage().contains("does not belong"), e.getMessage());
    }

    @Test
    void provisioningWithTheWrongNumberOfIdsIsRefused() {
        PoolKey pool = pool();

        // A partial mapping would provision a pool the allocator can allocate from
        // and cannot fully name, so the failure would arrive mid-spike with mask
        // bits already set.
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> allocator.provision(pool, 6, 4, arbitraryIds(pool, 3), List.of()));
        assertTrue(e.getMessage().contains("3 berth ids for 6 berths"), e.getMessage());
    }

    @Test
    void aPoolWithMasksButNoMappingSaysSoPrecisely() {
        PoolKey pool = pool();
        allocator.provision(pool, 3, 4, arbitraryIds(pool, 3), List.of());
        redis.del("berthids:" + pool.keySuffix());

        // Distinct from "pool not provisioned", which sends an operator looking for
        // a missing warm-up. This says the warm-up ran and did not supply a mapping,
        // which is a different bug in a different place.
        var cold = new RedisSeatAllocator(redis);
        var e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                cold.allocate(
                                        new AllocationRequest(
                                                pool,
                                                new SegmentRange(0, 4),
                                                1,
                                                "h5",
                                                NOW,
                                                TTL)));
        assertTrue(e.getMessage().contains("no berth id mapping"), e.getMessage());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static PoolKey pool() {
        return new PoolKey(NEXT_SCHEDULE.getAndIncrement(), TravelClass.SL, QuotaType.TATKAL);
    }

    /** Descending, non-contiguous, and independent of the pool's size. */
    private static List<Long> arbitraryIds(PoolKey pool, int berthCount) {
        var ids = new ArrayList<Long>(berthCount);
        for (int ordinal = 0; ordinal < berthCount; ordinal++) {
            ids.add(pool.scheduleId() * 1_000_000L + (64 - ordinal) * 3L + 1L);
        }
        return List.copyOf(ids);
    }
}
