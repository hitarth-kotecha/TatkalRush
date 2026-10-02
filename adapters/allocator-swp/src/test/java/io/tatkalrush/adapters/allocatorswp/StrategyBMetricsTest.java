package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-2.6's two counters exist under the names the SDD and the chaos scripts use,
 * from construction, at zero. No Kafka: the constructor does no I/O (see
 * {@link KafkaSeatAllocator#start}), so a registry assertion needs none either.
 *
 * <p>A counter that is only registered once the first event happens cannot be
 * told apart from one that was never wired - Prometheus would simply have no
 * series - so "zero under P1 and P3" must be an exported zero, not an absence.
 */
class StrategyBMetricsTest {

    @Test
    @DisplayName("dedup_evictions_before_window_expiry_total and orphaned_replies_total are exported at zero")
    void bothCountersExistFromConstruction() {
        var meters = new SimpleMeterRegistry();
        new KafkaSeatAllocator(
                "localhost:0",
                "c",
                "e",
                "r",
                Duration.ofSeconds(1),
                new InMemoryCheckpointStore(),
                new InMemoryHoldRoutingStore(),
                meters);

        var dedup = meters.find("dedup_evictions_before_window_expiry_total").counter();
        assertNotNull(dedup, "dedup eviction counter must be registered at construction");
        assertEquals(0.0, dedup.count());

        var orphaned = meters.find("orphaned_replies_total").functionCounter();
        assertNotNull(orphaned, "orphaned reply counter must be registered at construction");
        assertEquals(0.0, orphaned.count());
    }
}
