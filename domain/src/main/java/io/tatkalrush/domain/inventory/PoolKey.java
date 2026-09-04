package io.tatkalrush.domain.inventory;

/**
 * Identity of one quota pool: {@code (schedule, class, quota)} (FR-8).
 *
 * <p><b>This is the unit of contention.</b> Everything the project measures is
 * scoped to a pool: it is the granularity of a berth mask array, of a Redis key
 * (§10.5), and of a Kafka partition in Strategy B (§9.3). P3 — the profile that
 * discriminates the two strategies — is defined as "100% of load onto one
 * {@code (train, date, class)}", which is this key.
 *
 * <p>It is also the key that FR-15's availability cache needed {@code pool} added
 * to: GENERAL and TATKAL differ by definition (FR-10), so a cache keyed without
 * the quota serves one pool's answer for the other's question.
 *
 * @param scheduleId the {@code (train, journey_date)} instance
 * @param travelClass which class within it
 * @param quotaType which quota within that class
 */
public record PoolKey(long scheduleId, TravelClass travelClass, QuotaType quotaType) {

    public PoolKey {
        if (scheduleId <= 0) {
            throw new IllegalArgumentException("scheduleId must be positive, got " + scheduleId);
        }
        if (travelClass == null || quotaType == null) {
            throw new IllegalArgumentException("travelClass and quotaType are required");
        }
    }

    /**
     * The Redis key suffix for this pool (§10.5), e.g. {@code 42:SL:TATKAL}.
     *
     * <p>Defined here rather than in the Redis adapter because Strategy A's Lua
     * script and any operator running {@code redis-cli} both need to agree on it,
     * and because §13.4's rebuild-from-Postgres path has to reconstruct exactly
     * these keys after a {@code FLUSHALL}.
     */
    public String keySuffix() {
        return scheduleId + ":" + travelClass.code() + ":" + quotaType.code();
    }

    @Override
    public String toString() {
        return "pool(" + keySuffix() + ")";
    }
}
