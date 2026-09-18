package io.tatkalrush.adapters.allocatorswp;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.tatkalrush.domain.inventory.SegmentRange;
import java.util.List;

/**
 * A partition owner's answer, published to {@code booking-replies} (§9.3, milestone 1).
 *
 * <p>Every variant echoes {@code commandId} so the originating replica's reply
 * consumer can complete the matching pending future and nothing else.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PartitionReply.Ack.class, name = "ACK"),
    @JsonSubTypes.Type(value = PartitionReply.Allocated.class, name = "ALLOCATED"),
    @JsonSubTypes.Type(value = PartitionReply.Unavailable.class, name = "UNAVAILABLE"),
    @JsonSubTypes.Type(value = PartitionReply.Confirmed.class, name = "CONFIRMED"),
    @JsonSubTypes.Type(value = PartitionReply.HoldExpired.class, name = "HOLD_EXPIRED"),
    @JsonSubTypes.Type(value = PartitionReply.ReleaseConfirmedAck.class, name = "RELEASE_CONFIRMED_ACK"),
    @JsonSubTypes.Type(value = PartitionReply.AvailabilityAnswer.class, name = "AVAILABILITY_ANSWER"),
    @JsonSubTypes.Type(value = PartitionReply.ReapedCount.class, name = "REAPED_COUNT"),
    @JsonSubTypes.Type(value = PartitionReply.Failed.class, name = "FAILED"),
})
public sealed interface PartitionReply {

    String commandId();

    /** Provision and Release both just need "it happened". */
    record Ack(String commandId) implements PartitionReply {}

    record Allocated(
            String commandId, List<Long> berthIds, SegmentRange range, java.time.Instant expiresAt)
            implements PartitionReply {}

    record Unavailable(String commandId, int available, int requested) implements PartitionReply {}

    record Confirmed(String commandId, long bookingId, List<Long> berthIds) implements PartitionReply {}

    record HoldExpired(String commandId) implements PartitionReply {}

    record ReleaseConfirmedAck(String commandId, int cleared) implements PartitionReply {}

    record AvailabilityAnswer(String commandId, int freeBerths) implements PartitionReply {}

    record ReapedCount(String commandId, int reaped) implements PartitionReply {}

    /**
     * The owner threw applying the command. Carried as data rather than letting the
     * consumer loop die — one bad command must not stop the owner serving every
     * other pool on its partition.
     */
    record Failed(String commandId, String message) implements PartitionReply {}
}
