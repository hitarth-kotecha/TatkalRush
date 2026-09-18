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

    private PoolSnapshotCodec() {}

    static byte[] encode(int segmentCount, long[] masks, List<BerthPool.HoldSnapshot> holds) {
        var out = new ByteArrayOutputStream(64 + masks.length * 8);
        try (var data = new DataOutputStream(out)) {
            data.writeInt(segmentCount);
            data.writeInt(masks.length);
            for (long mask : masks) {
                data.writeLong(mask);
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

    record Decoded(int segmentCount, long[] masks, List<BerthPool.HoldSnapshot> holds) {}

    static Decoded decode(byte[] bytes) {
        try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int segmentCount = data.readInt();
            long[] masks = new long[data.readInt()];
            for (int i = 0; i < masks.length; i++) {
                masks[i] = data.readLong();
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
            return new Decoded(segmentCount, masks, holds);
        } catch (IOException e) {
            throw new IllegalStateException("failed to decode pool snapshot", e);
        }
    }
}
