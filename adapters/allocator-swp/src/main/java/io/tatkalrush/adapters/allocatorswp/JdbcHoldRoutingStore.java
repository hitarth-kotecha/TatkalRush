package io.tatkalrush.adapters.allocatorswp;

import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code hold_routing} (migration V10, §9.3, milestone 5). */
public final class JdbcHoldRoutingStore implements HoldRoutingStore {

    private final JdbcClient jdbc;

    public JdbcHoldRoutingStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void save(String holdId, String poolKey) {
        // ON CONFLICT DO NOTHING: a hold's pool never changes once allocated,
        // so a second write (a retried Allocate, deduped upstream but this
        // write is not part of that dedup) just confirms what is already there.
        jdbc.sql(
                        """
                        INSERT INTO hold_routing (hold_id, pool_key)
                        VALUES (:holdId, :poolKey)
                        ON CONFLICT (hold_id) DO NOTHING
                        """)
                .param("holdId", holdId)
                .param("poolKey", poolKey)
                .update();
    }

    @Override
    public Optional<String> load(String holdId) {
        return jdbc.sql("SELECT pool_key FROM hold_routing WHERE hold_id = :holdId")
                .param("holdId", holdId)
                .query(String.class)
                .optional();
    }

    @Override
    public void delete(String holdId) {
        jdbc.sql("DELETE FROM hold_routing WHERE hold_id = :holdId").param("holdId", holdId).update();
    }
}
