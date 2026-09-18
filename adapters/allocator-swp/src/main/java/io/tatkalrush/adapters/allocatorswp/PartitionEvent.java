package io.tatkalrush.adapters.allocatorswp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.time.Instant;
import java.util.List;

/**
 * A partition owner's write to {@code booking-events} — <b>the WAL</b> (§9.3).
 *
 * <p>Distinct from {@link PartitionCommand}: a command is a request, and its
 * outcome can depend on state the log does not otherwise capture (which berths
 * were free at that instant). Replay must not re-run the allocation algorithm
 * against reconstructed state and hope it makes the same choice — it must be
 * <b>told</b> what happened, verbatim. That is why {@link Allocated} carries the
 * chosen berth ids rather than the request that produced them, exactly as §9.3
 * says: "each {@code AllocationEvent} carries its {@code commandId} and allocated
 * berths."
 *
 * <p>Only state-changing outcomes get an event. {@code Unavailable},
 * {@code HoldExpired}, a release of an already-gone hold, and a
 * {@code releaseConfirmed}/{@code reapExpired} that cleared nothing left the
 * owner's state exactly as it was — replaying them would replay nothing, so
 * milestone 2 does not write them. (Milestone 3's replay consumer is what will
 * prove whether that omission is actually safe to rely on; recorded here so it is
 * a deliberate milestone boundary rather than a silent gap.)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PartitionEvent.Provisioned.class, name = "PROVISIONED"),
    @JsonSubTypes.Type(value = PartitionEvent.Allocated.class, name = "ALLOCATED"),
    @JsonSubTypes.Type(value = PartitionEvent.Released.class, name = "RELEASED"),
    @JsonSubTypes.Type(value = PartitionEvent.Confirmed.class, name = "CONFIRMED"),
    @JsonSubTypes.Type(value = PartitionEvent.ReleaseConfirmed.class, name = "RELEASE_CONFIRMED"),
    @JsonSubTypes.Type(value = PartitionEvent.Reaped.class, name = "REAPED"),
})
public sealed interface PartitionEvent {

    String commandId();

    /**
     * @param occupiedMasks confirmed occupancy per ordinal at provisioning time,
     *     or empty for a fresh pool (milestone 7). Carried in the WAL for the
     *     same reason {@link Allocated} carries its berths: a partition that
     *     replays this event after an owner dies must reproduce the occupied
     *     state exactly, not re-derive it from nothing.
     */
    record Provisioned(
            String commandId,
            PoolKey pool,
            int berthCount,
            int segmentCount,
            List<Long> berthIds,
            List<Long> occupiedMasks)
            implements PartitionEvent {}

    record Allocated(
            String commandId,
            PoolKey pool,
            String holdId,
            SegmentRange range,
            List<Long> berthIds,
            Instant expiresAt)
            implements PartitionEvent {}

    record Released(String commandId, PoolKey pool, String holdId) implements PartitionEvent {}

    record Confirmed(
            String commandId, PoolKey pool, String holdId, long bookingId, List<Long> berthIds)
            implements PartitionEvent {}

    record ReleaseConfirmed(
            String commandId, PoolKey pool, SegmentRange range, List<Long> berthIds, int cleared)
            implements PartitionEvent {}

    /** No per-hold detail: which holds were reaped is a deterministic function of
     * the reconstructed state and {@code now}, so replay recomputes it rather than
     * being told it (§9.3's owner already works this way internally). */
    record Reaped(String commandId, Instant now, int reaped) implements PartitionEvent {}
}
