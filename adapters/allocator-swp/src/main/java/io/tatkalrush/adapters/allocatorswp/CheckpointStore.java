package io.tatkalrush.adapters.allocatorswp;

import java.util.Optional;

/**
 * The {@code checkpoints} table (migration V7, §9.3, DD-013) — one row per pool,
 * not per Kafka partition. A pool and a partition are not the same thing: many
 * pools hash onto one partition, and the schema comment's "~5.6 KB" sizing
 * matches one pool's mask array, not an owner's whole partition. See
 * {@link KafkaSeatAllocator}'s class Javadoc for why this milestone writes
 * checkpoints but does not yet consult them at replay time.
 *
 * <p>Public (milestone 6): a composition root selecting Strategy B constructs
 * a {@link JdbcCheckpointStore} and hands it to {@link KafkaSeatAllocator}'s
 * constructor, so both the implementation and the parameter type it is passed
 * as need to be visible outside this package.
 */
public interface CheckpointStore {

    record Checkpoint(long kafkaOffset, long generationId, byte[] maskSnapshot) {}

    Optional<Checkpoint> load(String poolKey);

    /**
     * Generation-guarded write (DD-013, T-C10). A write from a stale generation
     * — a zombie owner that Kafka has already fenced from the log, but Postgres
     * has no way to know that — is silently rejected, not an error: the caller
     * has no way to distinguish "I was too slow" from "I am a zombie", and
     * §9.3 says the correct response to either is to do nothing.
     */
    void save(String poolKey, long kafkaOffset, long generationId, byte[] maskSnapshot);
}
