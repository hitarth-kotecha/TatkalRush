package io.tatkalrush.adapters.allocatorswp;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test double for {@link CheckpointStore}, with the same generation guard
 * {@link JdbcCheckpointStore} enforces in SQL (see that class and
 * {@code JdbcCheckpointStoreTest} for why the guard itself is tested against a
 * real Postgres separately — this fake exists so tests that only need "a
 * checkpoint store exists" do not have to pay for a container).
 */
final class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentHashMap<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();

    @Override
    public Optional<Checkpoint> load(String poolKey) {
        return Optional.ofNullable(checkpoints.get(poolKey));
    }

    @Override
    public void save(String poolKey, long kafkaOffset, long generationId, byte[] maskSnapshot) {
        checkpoints.merge(
                poolKey,
                new Checkpoint(kafkaOffset, generationId, maskSnapshot),
                (existing, incoming) -> incoming.generationId() < existing.generationId() ? existing : incoming);
    }
}
