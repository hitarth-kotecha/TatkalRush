package io.tatkalrush.domain.inventory;

/**
 * Booking scheme a pool belongs to (FR-8). Only these two exist (NG-3): no
 * concession, senior, ladies or defence quotas.
 *
 * <p>The two pools over one class hold <b>disjoint</b> berth sets. A berth in
 * both could be sold twice — once through each quota — while each pool's own mask
 * stayed perfectly consistent, so no allocator could detect it.
 */
public enum QuotaType {

    /** Always open. */
    GENERAL,

    /**
     * Locked until the Tatkal window opens (FR-10, FR-28). Sized at
     * {@code ceil(0.10 x class_capacity)}, minimum 1 berth (FR-9, DD-030).
     *
     * <p>Unlock is a pure function of clock time evaluated per request (FR-30) —
     * there is no job that "opens" a pool, because a job introduces a moment
     * where the pool's state depends on whether the job ran.
     */
    TATKAL;

    public String code() {
        return name();
    }

    public static QuotaType fromCode(String code) {
        return valueOf(code);
    }
}
