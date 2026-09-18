package io.tatkalrush.adapters.allocatorswp;

import java.util.Optional;

/**
 * The {@code hold_routing} table (migration V10, §9.3, milestone 5): a durable,
 * cross-replica {@code holdId -> pool key suffix} index.
 *
 * <p>Only consulted on a miss in {@link KafkaSeatAllocator}'s local, per-JVM
 * cache — see that class's Javadoc for why a same-replica call never reaches
 * this at all, and why that matters for keeping Postgres off the allocate hot
 * path.
 *
 * <p>Public (milestone 6), for the same reason as {@link CheckpointStore}: a
 * composition root outside this package needs to construct a
 * {@link JdbcHoldRoutingStore} and pass it to {@link KafkaSeatAllocator}.
 */
public interface HoldRoutingStore {

    /** Idempotent: writing the same {@code holdId} twice with the same pool is a no-op either way. */
    void save(String holdId, String poolKey);

    Optional<String> load(String holdId);

    /** Idempotent: deleting an already-gone or never-existing row is not an error. */
    void delete(String holdId);
}
