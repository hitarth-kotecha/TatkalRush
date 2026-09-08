package io.tatkalrush.ops.invariants;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Redis's view of one pool, decoded from raw bytes.
 *
 * <h2>Decoded here rather than read through the allocator</h2>
 *
 * <p>INV-8 exists to answer "does Redis agree with Postgres". Asking
 * {@code RedisSeatAllocator} for its own snapshot and comparing that against
 * Postgres would still be a real comparison — but it would pass through any bug
 * shared between the encoder and the decoder, and it would make the check depend
 * on the component most likely to be the one at fault.
 *
 * <p>So this parses the bytes. It has to know the <em>format</em> — 8 bytes per
 * berth, little-endian, low 32 bits then high 32 — and that much sharing is
 * unavoidable, because a format is what the two sides agreed on. What it must not
 * share is the <em>derivation</em>: nothing here computes what a mask ought to be.
 *
 * <h2>Why the halves are separate in Redis at all</h2>
 *
 * <p>Lua 5.1 inside Redis has no 64-bit integer type (FR-3a, DD-002), so a berth's
 * occupancy travels as two 32-bit words. Java has no such problem, so the two are
 * rejoined into one {@code long} the moment they are read — the split is a
 * constraint of the storage, not of the model.
 */
public record PoolSnapshot(long[] masks, int[] freeCounts) {

    /**
     * @param masksBytes {@code masks:<suffix>}, 8 bytes per berth
     * @param freeBytes {@code freecount:<suffix>}, 4 bytes per segment
     */
    public static PoolSnapshot decode(String masksBytes, String freeBytes) {
        // ISO-8859-1: Lettuce hands these back as Strings, and this is the only
        // charset that round-trips arbitrary bytes one-for-one. UTF-8 would
        // silently replace anything that is not valid UTF-8 - which for packed
        // binary is most of it - and the masks would decode to plausible-looking
        // nonsense rather than failing.
        byte[] maskRaw = masksBytes.getBytes(StandardCharsets.ISO_8859_1);
        byte[] freeRaw = freeBytes.getBytes(StandardCharsets.ISO_8859_1);

        if (maskRaw.length % 8 != 0) {
            throw new IllegalStateException(
                    "mask blob is %d bytes, not a multiple of 8".formatted(maskRaw.length));
        }
        if (freeRaw.length % 4 != 0) {
            throw new IllegalStateException(
                    "freecount blob is %d bytes, not a multiple of 4".formatted(freeRaw.length));
        }

        var maskBuffer = ByteBuffer.wrap(maskRaw).order(ByteOrder.LITTLE_ENDIAN);
        var masks = new long[maskRaw.length / 8];
        for (int i = 0; i < masks.length; i++) {
            long low = Integer.toUnsignedLong(maskBuffer.getInt());
            long high = Integer.toUnsignedLong(maskBuffer.getInt());
            // toUnsignedLong on both halves. A signed widening would sign-extend
            // any half with bit 31 set, filling the top of the long with ones -
            // a berth occupied on segment 31 would read as occupied on all of
            // 31..63, and INV-8 would report a violation that is entirely its own.
            masks[i] = (high << 32) | low;
        }

        var freeBuffer = ByteBuffer.wrap(freeRaw).order(ByteOrder.LITTLE_ENDIAN);
        var freeCounts = new int[freeRaw.length / 4];
        for (int i = 0; i < freeCounts.length; i++) {
            freeCounts[i] = freeBuffer.getInt();
        }

        return new PoolSnapshot(masks, freeCounts);
    }

    public int berthCount() {
        return masks.length;
    }

    public int segmentCount() {
        return freeCounts.length;
    }

    /**
     * INV-12's independent recomputation: berths free on each segment, counted
     * from the masks rather than read from the stored counter.
     */
    public int[] freeCountsFromMasks() {
        var recomputed = new int[freeCounts.length];
        for (int segment = 0; segment < recomputed.length; segment++) {
            long bit = 1L << segment;
            int free = 0;
            for (long mask : masks) {
                if ((mask & bit) == 0) {
                    free++;
                }
            }
            recomputed[segment] = free;
        }
        return recomputed;
    }
}
