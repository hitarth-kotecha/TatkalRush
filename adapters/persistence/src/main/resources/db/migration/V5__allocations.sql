-- V5: seat allocations, and THE constraint (SDD §10.2).
--
-- ============================================================================
-- This is the most important line of SQL in the project.
-- ============================================================================
--
-- no_overlapping_allocations makes overbooking STRUCTURALLY IMPOSSIBLE at the
-- storage layer, independently of whether either allocator has a bug. Not
-- "unlikely", not "validated" - the database will refuse the write.
--
-- Why a constraint and not an application check: application-level validation is
-- exactly what fails under the concurrency this project exists to survive. Two
-- threads can both read "berth 7 is free for [0,4)" before either writes, and
-- both then write. A constraint is evaluated by the one component that sees
-- every writer.
--
-- HOW IT WORKS. EXCLUDE generalises UNIQUE. Where UNIQUE says "no two rows are
-- equal on these columns", EXCLUDE says "no two rows are RELATED by these
-- operators": same schedule (=), same berth (=), and segment ranges that
-- overlap (&&). It is UNIQUE with a richer notion of collision.
--
-- The && operator needs a GiST index, because a B-tree only understands
-- ordering and equality and cannot answer "do these two ranges intersect?".
-- btree_gist (V1) supplies the scalar-equality operator classes so schedule_id
-- and berth_id can share that same GiST index.
--
-- WHAT HAPPENS WHEN IT FIRES (FR-25, DD-008). If both allocators are correct,
-- this constraint can NEVER trip. A firing is therefore not an edge case to
-- handle gracefully - it is a detector announcing that an allocator bug reached
-- production, at a moment when the customer's money has already been captured.
--
--   * Confirmation validates the hold is live BEFORE attempting this insert, so
--     a benign expiry race (FR-24, expected during C2) is separable from a real
--     allocator defect.
--   * A conflict against a LIVE hold auto-refunds with
--     refunds.reason = 'ALLOCATION_CONFLICT', increments
--     allocation_constraint_violations_total, and FAILS THE RUN under NFR-9.
--
-- Without that handling the constraint converts a data bug into a money bug
-- while INV-1, INV-2 and every other §14 check still report green.

CREATE TABLE seat_allocations (
    id          BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT      NOT NULL REFERENCES schedules (id),
    berth_id    BIGINT      NOT NULL REFERENCES berths (id),
    booking_id  BIGINT      NOT NULL REFERENCES bookings (id),

    -- Half-open [from_seq, to_seq), matching §5.2 and bookings.from_seq/to_seq.
    --
    -- The half-openness is load-bearing, not a style choice. Delhi->Ratlam
    -- [0,2) and Ratlam->Mumbai [2,4) must NOT overlap - they share only the
    -- instant at Ratlam, not a leg - so both can occupy one berth (test T-3).
    -- With inclusive ranges they would collide and the system would refuse a
    -- booking that real railways accept, turning the project's central
    -- capability into a bug.
    seg_range   INT4RANGE   NOT NULL,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT no_overlapping_allocations EXCLUDE USING gist (
        schedule_id WITH =,
        berth_id    WITH =,
        seg_range   WITH &&
    )
);

COMMENT ON CONSTRAINT no_overlapping_allocations ON seat_allocations IS
    'INV-1 enforced in the storage layer. A correct allocator can never trip '
    'this; a trip means an allocator bug shipped and money was already taken '
    '(FR-25, DD-008, INV-11). Non-negotiable under NFR-9.';

-- Enforce that the range is well-formed and canonical. Postgres normalises
-- int4range to [) form, but an explicitly empty range would satisfy every
-- overlap test vacuously and slip past the constraint entirely.
ALTER TABLE seat_allocations
    ADD CONSTRAINT seg_range_is_non_empty CHECK (NOT isempty(seg_range));

ALTER TABLE seat_allocations
    ADD CONSTRAINT seg_range_within_route CHECK (
        lower(seg_range) >= 0 AND upper(seg_range) <= 64
    );

CREATE INDEX idx_seat_allocations_booking  ON seat_allocations (booking_id);
CREATE INDEX idx_seat_allocations_schedule ON seat_allocations (schedule_id);
