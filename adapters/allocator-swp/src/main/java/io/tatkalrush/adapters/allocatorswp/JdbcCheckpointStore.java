package io.tatkalrush.adapters.allocatorswp;

import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code checkpoints} (migration V7, §9.3, DD-013).
 *
 * <p>{@link #save} is one atomic {@code INSERT ... ON CONFLICT DO UPDATE}, not an
 * {@code UPDATE} followed by a fallback {@code INSERT}. Two owners can both reach
 * "no checkpoint exists yet for this pool" for the same pool at the same time —
 * a zombie owner is fenced from Kafka, never from Postgres — and a two-statement
 * version has a window where both see zero rows updated and both attempt the
 * insert, one of them hitting the primary key it should have just deferred to.
 * {@code ON CONFLICT}'s guard clause runs inside the same statement Postgres
 * already serialises per row, so there is no window to race in.
 */
public final class JdbcCheckpointStore implements CheckpointStore {

    private final JdbcClient jdbc;

    public JdbcCheckpointStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public Optional<Checkpoint> load(String poolKey) {
        return jdbc.sql(
                        "SELECT kafka_offset, generation_id, mask_snapshot FROM checkpoints"
                                + " WHERE partition_key = :poolKey")
                .param("poolKey", poolKey)
                .query(
                        (rs, rowNum) ->
                                new Checkpoint(
                                        rs.getLong("kafka_offset"),
                                        rs.getLong("generation_id"),
                                        rs.getBytes("mask_snapshot")))
                .optional();
    }

    @Override
    public void save(String poolKey, long kafkaOffset, long generationId, byte[] maskSnapshot) {
        jdbc.sql(
                        """
                        INSERT INTO checkpoints (partition_key, kafka_offset, generation_id, mask_snapshot, updated_at)
                        VALUES (:poolKey, :offset, :generation, :snapshot, now())
                        ON CONFLICT (partition_key) DO UPDATE
                           SET kafka_offset  = EXCLUDED.kafka_offset,
                               generation_id = EXCLUDED.generation_id,
                               mask_snapshot = EXCLUDED.mask_snapshot,
                               updated_at    = now()
                         WHERE checkpoints.generation_id <= EXCLUDED.generation_id
                        """)
                .param("poolKey", poolKey)
                .param("offset", kafkaOffset)
                .param("generation", generationId)
                .param("snapshot", maskSnapshot)
                .update();
    }
}
