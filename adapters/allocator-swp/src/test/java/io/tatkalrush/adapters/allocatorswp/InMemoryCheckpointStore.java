package io.tatkalrush.adapters.allocatorswp;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test double for {@link CheckpointStore}, with the same generation guard and the
 * same advance rules {@link JdbcCheckpointStore} enforces in SQL (see that class
 * and {@code JdbcCheckpointStoreTest} for why the guard itself is tested against a
 * real Postgres separately — this fake exists so tests that only need "a
 * checkpoint store exists" do not have to pay for a container).
 */
final class InMemoryCheckpointStore implements CheckpointStore {

    private record Row(int partition, Checkpoint checkpoint) {}

    private final ConcurrentHashMap<String, Row> rows = new ConcurrentHashMap<>();

    @Override
    public Optional<Checkpoint> load(String poolKey) {
        return Optional.ofNullable(rows.get(poolKey)).map(Row::checkpoint);
    }

    @Override
    public Map<String, Checkpoint> loadPartition(int partition) {
        var out = new HashMap<String, Checkpoint>();
        rows.forEach(
                (key, row) -> {
                    if (row.partition() == partition) {
                        out.put(key, row.checkpoint());
                    }
                });
        return out;
    }

    @Override
    public boolean save(
            String poolKey, int partition, long kafkaOffset, long generationId, byte[] maskSnapshot) {
        var written = new boolean[] {false};
        rows.compute(
                poolKey,
                (key, existing) -> {
                    if (existing != null && generationId < existing.checkpoint().generationId()) {
                        return existing;
                    }
                    written[0] = true;
                    return new Row(partition, new Checkpoint(kafkaOffset, generationId, maskSnapshot));
                });
        return written[0];
    }

    @Override
    public int advance(int partition, long generationId, long offset, Collection<String> poolKeys) {
        int moved = 0;
        for (String key : poolKeys) {
            var movedHere = new boolean[] {false};
            rows.computeIfPresent(
                    key,
                    (k, row) -> {
                        var cp = row.checkpoint();
                        if (row.partition() == partition
                                && cp.generationId() <= generationId
                                && cp.kafkaOffset() >= 0
                                && cp.kafkaOffset() < offset) {
                            movedHere[0] = true;
                            return new Row(
                                    partition, new Checkpoint(offset, cp.generationId(), cp.maskSnapshot()));
                        }
                        return row;
                    });
            if (movedHere[0]) {
                moved++;
            }
        }
        return moved;
    }
}
