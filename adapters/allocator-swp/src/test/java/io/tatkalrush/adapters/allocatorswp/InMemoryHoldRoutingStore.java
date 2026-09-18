package io.tatkalrush.adapters.allocatorswp;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A test double for {@link HoldRoutingStore} — see {@code JdbcHoldRoutingStoreTest} for the real thing. */
final class InMemoryHoldRoutingStore implements HoldRoutingStore {

    private final ConcurrentHashMap<String, String> routes = new ConcurrentHashMap<>();

    @Override
    public void save(String holdId, String poolKey) {
        routes.putIfAbsent(holdId, poolKey);
    }

    @Override
    public Optional<String> load(String holdId) {
        return Optional.ofNullable(routes.get(holdId));
    }

    @Override
    public void delete(String holdId) {
        routes.remove(holdId);
    }
}
