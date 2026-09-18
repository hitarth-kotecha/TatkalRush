package io.tatkalrush.adapters.allocatorswp;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The bounded, time-evicted {@code commandId -> reply} cache §9.3 requires
 * (DD-009): "not a set of IDs" — on a duplicate it re-publishes the cached
 * <b>reply</b>, so a client retry gets its original {@code holdId} and berths
 * back rather than an error or, worse, a second allocation under the same
 * commandId. See {@link KafkaSeatAllocator}'s class Javadoc for the scenario
 * this protects against: a Kafka round trip that the owner completed but whose
 * reply never reached the originating replica.
 *
 * <p>Not thread-safe, deliberately, same as {@link PartitionOwner}: only the
 * partition's single consumer thread ever touches one of these.
 *
 * <p><b>Two independent eviction paths</b>, both named in §9.3:
 *
 * <ul>
 *   <li><b>Time.</b> An entry older than {@code ttl} (§9.3's "roughly 60 seconds
 *       ... one retry") is swept on the next {@link #get}/{@link #put}.
 *   <li><b>Size.</b> Past {@code maxEntries} (§9.3's "~5,000 per hot partition"),
 *       the oldest-inserted entry is evicted regardless of its remaining TTL.
 *       If that entry had not yet expired, {@link #prematureEvictions()}
 *       increments — the signal that the cache is undersized for the load it is
 *       actually seeing, which is the metric this class exists to make
 *       observable rather than a size chosen once and never checked.
 * </ul>
 */
final class DedupCache {

    private record CachedReply(PartitionReply reply, Instant expiresAt) {}

    private final Duration ttl;
    private final Map<String, CachedReply> entries;
    private long prematureEvictions = 0;

    /**
     * The caller's clock reading at the point of the {@code put}/{@code
     * putWithExpiry} that may trigger {@link #removeEldestEntry}. That callback's
     * signature is fixed by {@link LinkedHashMap} and cannot take a clock
     * parameter directly — this field is how it still judges "premature" against
     * the same simulated clock every other method here uses, rather than the
     * real wall clock, which would make {@link #prematureEvictions()} wrong for
     * any test (or replay) not running in real time.
     */
    private Instant clockAtLastInsert;

    DedupCache(int maxEntries, Duration ttl) {
        this.ttl = ttl;
        this.entries =
                new LinkedHashMap<>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, CachedReply> eldest) {
                        if (size() <= maxEntries) {
                            return false;
                        }
                        if (eldest.getValue().expiresAt().isAfter(clockAtLastInsert)) {
                            prematureEvictions++;
                        }
                        return true;
                    }
                };
    }

    /** The cached reply for a commandId already seen and not yet evicted, if any. */
    Optional<PartitionReply> get(String commandId, Instant now) {
        evictExpired(now);
        CachedReply entry = entries.get(commandId);
        return entry == null ? Optional.empty() : Optional.of(entry.reply());
    }

    void put(String commandId, PartitionReply reply, Instant now) {
        clockAtLastInsert = now;
        entries.put(commandId, new CachedReply(reply, now.plus(ttl)));
    }

    /**
     * Seeds an entry from WAL replay with an explicit expiry, rather than
     * {@code now + ttl} — replay is reconstructing something that happened in
     * the past, and an entry from 55 seconds ago has 5 seconds left, not a
     * fresh 60.
     */
    void putWithExpiry(String commandId, PartitionReply reply, Instant expiresAt, Instant now) {
        clockAtLastInsert = now;
        entries.put(commandId, new CachedReply(reply, expiresAt));
    }

    private void evictExpired(Instant now) {
        entries.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
    }

    /** §9.3's {@code dedup_evictions_before_window_expiry_total}. */
    long prematureEvictions() {
        return prematureEvictions;
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
    }
}
