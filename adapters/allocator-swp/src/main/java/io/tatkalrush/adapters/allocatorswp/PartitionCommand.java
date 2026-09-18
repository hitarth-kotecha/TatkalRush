package io.tatkalrush.adapters.allocatorswp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.tatkalrush.domain.inventory.PoolKey;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.time.Instant;
import java.util.List;

/**
 * A booking command as it crosses {@code booking-commands} (§9.3, milestone 1).
 *
 * <p>Milestone 1 only: no transactional envelope, no WAL persistence, no
 * generation stamp. Those arrive with fencing and checkpointing (§9.3's later
 * paragraphs) once the basic request/reply round trip is proven. See
 * {@link PartitionOwner} for what that means for correctness today.
 *
 * <p>{@code replyPartition} is carried on every command because the SDD's
 * pseudocode requires it: the owner must know which {@code booking-replies}
 * partition the <em>originating</em> replica is reading, since any replica may
 * have issued the command. Milestone 1 runs single-instance and always sets it
 * to {@code 0}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PartitionCommand.Provision.class, name = "PROVISION"),
    @JsonSubTypes.Type(value = PartitionCommand.Allocate.class, name = "ALLOCATE"),
    @JsonSubTypes.Type(value = PartitionCommand.Release.class, name = "RELEASE"),
    @JsonSubTypes.Type(value = PartitionCommand.Confirm.class, name = "CONFIRM"),
    @JsonSubTypes.Type(value = PartitionCommand.ReleaseConfirmed.class, name = "RELEASE_CONFIRMED"),
    @JsonSubTypes.Type(value = PartitionCommand.Availability.class, name = "AVAILABILITY"),
    @JsonSubTypes.Type(value = PartitionCommand.ReapExpired.class, name = "REAP_EXPIRED"),
})
public sealed interface PartitionCommand {

    String commandId();

    int replyPartition();

    /**
     * @param occupiedMasks confirmed occupancy per ordinal, or empty for a fresh
     *     pool (milestone 7) — see {@code PartitionOwner.provision}'s five-arg
     *     overload.
     */
    record Provision(
            String commandId,
            int replyPartition,
            PoolKey pool,
            int berthCount,
            int segmentCount,
            List<Long> berthIds,
            List<Long> occupiedMasks)
            implements PartitionCommand {}

    /** {@code commandId} is the request's {@code holdId} (DD-009): one identity edge-to-owner. */
    record Allocate(
            String commandId,
            int replyPartition,
            PoolKey pool,
            SegmentRange range,
            int passengerCount,
            String holdId,
            Instant now,
            long ttlMillis)
            implements PartitionCommand {}

    record Release(String commandId, int replyPartition, String holdId) implements PartitionCommand {}

    record Confirm(String commandId, int replyPartition, String holdId, long bookingId)
            implements PartitionCommand {}

    record ReleaseConfirmed(
            String commandId, int replyPartition, PoolKey pool, SegmentRange range, List<Long> berthIds)
            implements PartitionCommand {}

    record Availability(String commandId, int replyPartition, PoolKey pool, SegmentRange range)
            implements PartitionCommand {}

    /**
     * Fanned out to every partition (§13.2 for Strategy B: "the partition owner
     * reaps what it owns"). Carries no pool — each owner reaps everything it has.
     */
    record ReapExpired(String commandId, int replyPartition, Instant now) implements PartitionCommand {}
}
