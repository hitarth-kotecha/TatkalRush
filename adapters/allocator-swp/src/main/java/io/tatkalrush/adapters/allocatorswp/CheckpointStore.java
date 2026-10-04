package io.tatkalrush.adapters.allocatorswp;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code checkpoints} table (migrations V7 and V11, §9.3, DD-013) — one row per
 * pool, tagged with the Kafka partition whose owner wrote it. A pool and a
 * partition are not the same thing: many pools hash onto one partition, so a
 * partition's recovery loads <em>all</em> of its pools' rows.
 *
 * <p>{@code kafkaOffset} means "this snapshot is complete through this offset of
 * the partition's events topic", whichever pool those events were for — not "the
 * last event for this pool". That is what lets a pool nobody has touched for an
 * hour still say "I am current as of a moment ago", so recovery's starting point
 * is set by recent activity, not by the quietest pool.
 *
 * <p>A negative offset is a <b>presence marker</b>: the pool exists, no state is
 * claimed. Recovery treats any marker as "the partition's checkpoints are not yet
 * a usable set" and replays everything.
 *
 * <p>Public (milestone 6): a composition root selecting Strategy B constructs
 * a {@link JdbcCheckpointStore} and hands it to {@link KafkaSeatAllocator}'s
 * constructor, so both the implementation and the parameter type it is passed
 * as need to be visible outside this package.
 */
public interface CheckpointStore {

    record Checkpoint(long kafkaOffset, long generationId, byte[] maskSnapshot) {}

    Optional<Checkpoint> load(String poolKey);

    /** Every pool's row for one partition: pool key suffix to its checkpoint. */
    Map<String, Checkpoint> loadPartition(int partition);

    /**
     * Generation-guarded write (DD-013, T-C10). A write from a stale generation
     * — a zombie owner that Kafka has already fenced from the log, but Postgres
     * has no way to know that — is silently rejected, not an error: the caller
     * has no way to distinguish "I was too slow" from "I am a zombie", and
     * §9.3 says the correct response to either is to do nothing.
     *
     * @return whether the row was written. {@code false} means a newer generation
     *     already owns it - callers must not record the write as persisted.
     */
    boolean save(String poolKey, int partition, long kafkaOffset, long generationId, byte[] maskSnapshot);

    /**
     * Moves already-persisted snapshots forward to a later offset WITHOUT
     * rewriting them. Only valid for pools whose state has not changed since the
     * snapshot - the caller's job to know - and it only ever raises an offset.
     * This is what keeps an idle pool from pinning a partition's recovery
     * starting point at the moment it was last booked.
     *
     * @return how many rows moved; fewer than {@code poolKeys.size()} means some
     *     were already at or past {@code offset}, or lost to a newer generation
     */
    int advance(int partition, long generationId, long offset, Collection<String> poolKeys);
}
