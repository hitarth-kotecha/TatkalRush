package io.tatkalrush.adapters.allocatorswp;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code checkpoints} (migrations V7 and V11, §9.3, DD-013).
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
    public Map<String, Checkpoint> loadPartition(int partition) {
        var rows = new HashMap<String, Checkpoint>();
        jdbc.sql(
                        "SELECT partition_key, kafka_offset, generation_id, mask_snapshot"
                                + " FROM checkpoints WHERE kafka_partition = :partition")
                .param("partition", partition)
                .query(
                        (rs, rowNum) -> {
                            rows.put(
                                    rs.getString("partition_key"),
                                    new Checkpoint(
                                            rs.getLong("kafka_offset"),
                                            rs.getLong("generation_id"),
                                            rs.getBytes("mask_snapshot")));
                            return rowNum;
                        })
                .list();
        return rows;
    }

    @Override
    public boolean save(
            String poolKey, int partition, long kafkaOffset, long generationId, byte[] maskSnapshot) {
        return jdbc.sql(
                                """
                                INSERT INTO checkpoints
                                    (partition_key, kafka_partition, kafka_offset, generation_id, mask_snapshot, updated_at)
                                VALUES (:poolKey, :partition, :offset, :generation, :snapshot, now())
                                ON CONFLICT (partition_key) DO UPDATE
                                   SET kafka_partition = EXCLUDED.kafka_partition,
                                       kafka_offset    = EXCLUDED.kafka_offset,
                                       generation_id   = EXCLUDED.generation_id,
                                       mask_snapshot   = EXCLUDED.mask_snapshot,
                                       updated_at      = now()
                                 WHERE checkpoints.generation_id <= EXCLUDED.generation_id
                                """)
                        .param("poolKey", poolKey)
                        .param("partition", partition)
                        .param("offset", kafkaOffset)
                        .param("generation", generationId)
                        .param("snapshot", maskSnapshot)
                        .update()
                > 0;
    }

    @Override
    public int advance(int partition, long generationId, long offset, Collection<String> poolKeys) {
        if (poolKeys.isEmpty()) {
            return 0;
        }
        // generation_id is deliberately NOT rewritten: the snapshot's bytes are
        // still the previous owner's, and the guard on save() compares against
        // the generation that WROTE them.
        return jdbc.sql(
                        """
                        UPDATE checkpoints
                           SET kafka_offset = :offset, updated_at = now()
                         WHERE kafka_partition = :partition
                           AND partition_key IN (:poolKeys)
                           AND generation_id <= :generation
                           AND kafka_offset >= 0
                           AND kafka_offset < :offset
                        """)
                .param("offset", offset)
                .param("partition", partition)
                .param("poolKeys", poolKeys)
                .param("generation", generationId)
                .update();
    }
}
