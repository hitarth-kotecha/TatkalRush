-- V7: waitlist, outbox, idempotency and Strategy B checkpoints (SDD §10.4).

-- ---------------------------------------------------------------------------
-- Waitlist. Position is DERIVED, never stored (DD-011).
--
-- v1.1 stored a contiguous `position` and required INV-9 to keep it contiguous.
-- That is incompatible with FR-41, which promotes "the oldest entry WHOSE RANGE
-- FITS" - age and fit are independent, so nearly every promotion would leave a
-- gap and force a renumber of the queue tail.
--
-- Under P5 (1,000 cancellations/sec against a ~700-berth class) that is roughly
-- 245,000 serialised row-writes per second on a unique index. It does not
-- degrade; it deadlocks.
--
-- So: `seq` is monotonic arrival order and is NEVER renumbered. Promotion sets
-- promoted_at in place - two row writes per cancellation instead of ~245.
-- Displayed position is a ROW_NUMBER() window at read time, which makes
-- contiguity a property of the QUERY rather than something to defend with locks.
-- ---------------------------------------------------------------------------
CREATE TABLE waitlist_entries (
    id           BIGSERIAL PRIMARY KEY,
    schedule_id  BIGINT NOT NULL REFERENCES schedules (id),
    travel_class TEXT   NOT NULL CHECK (travel_class IN ('SL', '3A', '2A', '1A', 'CC')),
    booking_id   BIGINT NOT NULL REFERENCES bookings (id),

    -- Monotonic arrival order within (schedule, class, entry_type). Never
    -- renumbered, never reused.
    seq          BIGINT NOT NULL,

    entry_type   TEXT   NOT NULL CHECK (entry_type IN ('RAC', 'WL')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL means still waiting. Set once, on promotion.
    promoted_at  TIMESTAMPTZ,

    -- INV-9 as rewritten: seq unique and strictly increasing per partition.
    UNIQUE (schedule_id, travel_class, entry_type, seq)
);

-- FR-41 becomes a single indexed "ORDER BY seq LIMIT 1" with a range predicate.
-- Partial on promoted_at IS NULL so the index holds only the ACTIVE queue, not
-- every entry ever promoted during a soak.
--
-- Open question worth measuring (design-decisions.md, DD-011): does this index
-- genuinely keep the ROW_NUMBER() read cheap at P5's cancellation rate, or does
-- it move cost from writes to reads without reducing it? AC-3a.3 measures it.
CREATE INDEX idx_waitlist_active
    ON waitlist_entries (schedule_id, travel_class, entry_type, seq)
    WHERE promoted_at IS NULL;

CREATE INDEX idx_waitlist_booking ON waitlist_entries (booking_id);

-- ---------------------------------------------------------------------------
-- Transactional outbox: events are written in the SAME transaction as the state
-- change that produced them, then published separately. Without this, a crash
-- between "commit booking" and "publish event" silently loses the event, and
-- no invariant would notice.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at)
    WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- Idempotency. Stores a REFERENCE, not a frozen response (FR-19, DD-010).
--
-- A frozen 200 replayed at t=300 s would assert a hold that expired at t=120 s.
-- Replay instead re-renders from CURRENT booking state, so a duplicate request
-- sees EXPIRED or CONFIRMED-with-PNR, whichever is true now.
--
-- T-5's mechanism depends on this table's primary key: the key row is inserted
-- FIRST, in a transaction, BEFORE allocating. The 99 losers resolve to the
-- winner's booking_id on the unique-constraint conflict. Written as
-- check-then-act, T-5 is intermittently flaky in a way that reads as a
-- load-test artifact rather than a race.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    key          TEXT PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users (id),

    -- Same key with a DIFFERENT request body is a client bug, not a retry:
    -- it returns 409 IDEMPOTENCY_KEY_REUSED (§11.2) rather than silently
    -- serving the first request's answer for a different question.
    request_hash TEXT   NOT NULL,

    booking_id   BIGINT REFERENCES bookings (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);

-- ---------------------------------------------------------------------------
-- Strategy B partition checkpoints (§9.3, DD-013). Unused until Phase 2.
--
-- The generation guard is the point of this table. Kafka's producer-epoch
-- fencing stops a zombie owner writing to KAFKA, but nothing stops it writing
-- to POSTGRES. Without the guard, a zombie's stale checkpoint overwrites the
-- good one, the next owner replays from a wrong offset, and there is no error
-- anywhere - INV-8 only notices after the run.
--
-- Writes must therefore be: UPDATE ... WHERE generation_id <= :myGeneration
-- and loads must take the highest generation_id.
-- ---------------------------------------------------------------------------
CREATE TABLE checkpoints (
    partition_key TEXT PRIMARY KEY,

    -- The last COMMITTED transactional offset. Never ahead of the durable WAL,
    -- or replay would skip commands that were never applied.
    kafka_offset  BIGINT NOT NULL,

    generation_id BIGINT NOT NULL,

    -- The berth mask array, copied off the consumer thread before writing
    -- (~5.6 KB). Copied, not shared: a shared array tears mid-mutation and the
    -- snapshot is then of a state that never existed.
    mask_snapshot BYTEA  NOT NULL,

    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN checkpoints.generation_id IS
    'Fences a zombie owner writing to Postgres. Kafka producer epochs cannot: '
    'they only fence writes to Kafka (DD-013, T-C10).';
