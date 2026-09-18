package io.tatkalrush.adapters.allocatorswp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link DedupCache} in isolation: the two eviction paths §9.3 names, checked separately. */
class DedupCacheTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private static PartitionReply.Ack ack(String id) {
        return new PartitionReply.Ack(id);
    }

    @Test
    @DisplayName("a cached reply is returned for its commandId and no other")
    void returnsCachedReplyForItsOwnId() {
        var cache = new DedupCache(100, Duration.ofSeconds(60));
        cache.put("a", ack("a"), T0);

        assertEquals(ack("a"), cache.get("a", T0).orElseThrow());
        assertTrue(cache.get("b", T0).isEmpty());
    }

    @Test
    @DisplayName("an entry past its TTL is gone, and does not count as a premature eviction")
    void ttlExpiryIsNotPremature() {
        var cache = new DedupCache(100, Duration.ofSeconds(60));
        cache.put("a", ack("a"), T0);

        assertTrue(cache.get("a", T0.plusSeconds(61)).isEmpty(), "60s TTL elapsed");
        assertEquals(0, cache.prematureEvictions());
    }

    @Test
    @DisplayName("an entry exactly at its TTL boundary is still expired (exclusive upper bound)")
    void ttlBoundaryIsExclusive() {
        var cache = new DedupCache(100, Duration.ofSeconds(60));
        cache.put("a", ack("a"), T0);

        assertTrue(cache.get("a", T0.plusSeconds(60)).isEmpty());
    }

    @Test
    @DisplayName("exceeding maxEntries evicts the oldest insertion, counted as premature if unexpired")
    void sizeCapEvictsOldestAndCountsPremature() {
        var cache = new DedupCache(2, Duration.ofSeconds(60));
        cache.put("a", ack("a"), T0);
        cache.put("b", ack("b"), T0);
        cache.put("c", ack("c"), T0); // forces "a" out, well before its 60s TTL

        assertEquals(2, cache.size());
        assertTrue(cache.get("a", T0).isEmpty(), "oldest insertion was evicted for space");
        assertEquals(1, cache.prematureEvictions());
    }

    @Test
    @DisplayName("a size-cap eviction of an already-expired entry is not premature")
    void sizeCapEvictionOfExpiredEntryIsNotPremature() {
        var cache = new DedupCache(2, Duration.ofSeconds(60));
        cache.put("a", ack("a"), T0);
        // "a" is now expired by the time "b" and "c" arrive, well past T0 + 60s.
        cache.put("b", ack("b"), T0.plusSeconds(120));
        cache.put("c", ack("c"), T0.plusSeconds(120));

        assertEquals(0, cache.prematureEvictions(), "a" + " had already expired when evicted for space");
    }

    @Test
    @DisplayName("putWithExpiry (replay) honours an explicit expiry rather than now + ttl")
    void putWithExpiryHonoursExplicitExpiry() {
        var cache = new DedupCache(100, Duration.ofSeconds(60));
        // Reconstructing an entry that, at replay time, has only 5s left.
        cache.putWithExpiry("a", ack("a"), T0.plusSeconds(5), T0);

        assertTrue(cache.get("a", T0.plusSeconds(4)).isPresent());
        assertTrue(cache.get("a", T0.plusSeconds(6)).isEmpty());
    }
}
