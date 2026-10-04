package io.tatkalrush.adapters.allocatorswp;

import io.tatkalrush.domain.inventory.BerthPool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The binary format of {@code checkpoints.mask_snapshot} (§9.3, migration V7).
 *
 * <p>A custom binary encoding, not JSON: the schema comment sizes the snapshot at
 * "~5.6 KB for a large class", and a checkpoint fires every 5 seconds per pool
 * this owner holds — JSON's per-field overhead and the cost of building a tree of
 * boxed objects to describe an array of longs is exactly the kind of thing the
 * hot path this project measures cannot absorb for free. Every other Kafka
 * payload in this module uses JSON because those are low-frequency, human-worth-
 * reading control messages; this one is neither.
 */
final class PoolSnapshotCodec {

    /**
     * Format marker, so a checkpoint written before berth ids joined the snapshot
     * is recognised and refused rather than misread: its first int is a segment
     * count, which can never equal this. Recovery treats a refusal as "no usable
     * checkpoint" and falls back to a full replay.
     */
    private static final int MAGIC = 0x50534E32; // "PSN2"

    private PoolSnapshotCodec() {}

    /**
     * @param berthIds {@code berths.id} per ordinal. They travel with the snapshot
     *     because recovery restores a pool from it WITHOUT replaying the pool's
     *     {@code Provisioned} event, which is where they otherwise come from; an
     *     owner that invented them would corrupt a booking silently.
     */
    static byte[] encode(
            int segmentCount, long[] masks, long[] berthIds, List<BerthPool.HoldSnapshot> holds) {
        var out = new ByteArrayOutputStream(64 + masks.length * 16);
        try (var data = new DataOutputStream(out)) {
            data.writeInt(MAGIC);
            data.writeInt(segmentCount);
            data.writeInt(masks.length);
            for (long mask : masks) {
                data.writeLong(mask);
            }
            data.writeInt(berthIds.length);
            for (long berthId : berthIds) {
                data.writeLong(berthId);
            }
            data.writeInt(holds.size());
            for (BerthPool.HoldSnapshot hold : holds) {
                data.writeUTF(hold.holdId());
                data.writeLong(hold.expiresAt().toEpochMilli());
                data.writeLong(hold.requestMask());
                data.writeInt(hold.berthOrdinals().size());
                for (int ordinal : hold.berthOrdinals()) {
                    data.writeInt(ordinal);
                }
            }
        } catch (IOException e) {
            // ByteArrayOutputStream never throws IOException in practice; this
            // exists only because DataOutputStream's signature demands a catch.
            throw new IllegalStateException("failed to encode pool snapshot", e);
        }
        return out.toByteArray();
    }

    record Decoded(
            int segmentCount, long[] masks, long[] berthIds, List<BerthPool.HoldSnapshot> holds) {}

    static Decoded decode(byte[] bytes) {
        try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (data.readInt() != MAGIC) {
                throw new IllegalStateException("pool snapshot is not in the current format");
            }
            int segmentCount = data.readInt();
            long[] masks = new long[data.readInt()];
            for (int i = 0; i < masks.length; i++) {
                masks[i] = data.readLong();
            }
            long[] berthIds = new long[data.readInt()];
            for (int i = 0; i < berthIds.length; i++) {
                berthIds[i] = data.readLong();
            }

            int holdCount = data.readInt();
            var holds = new ArrayList<BerthPool.HoldSnapshot>(holdCount);
            for (int i = 0; i < holdCount; i++) {
                String holdId = data.readUTF();
                Instant expiresAt = Instant.ofEpochMilli(data.readLong());
                long requestMask = data.readLong();
                int ordinalCount = data.readInt();
                var ordinals = new ArrayList<Integer>(ordinalCount);
                for (int j = 0; j < ordinalCount; j++) {
                    ordinals.add(data.readInt());
                }
                holds.add(new BerthPool.HoldSnapshot(holdId, ordinals, requestMask, expiresAt));
            }
            return new Decoded(segmentCount, masks, berthIds, holds);
        } catch (IOException e) {
            // Includes EOFException: a truncated or old-format snapshot.
            throw new IllegalStateException("failed to decode pool snapshot", e);
        }
    }
}
