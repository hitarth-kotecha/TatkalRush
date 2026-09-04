-- V4: bookings and passengers (SDD §10.1, state machine §6.4).

CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    pnr             TEXT   NOT NULL UNIQUE,
    schedule_id     BIGINT NOT NULL REFERENCES schedules (id),
    travel_class    TEXT   NOT NULL CHECK (travel_class IN ('SL', '3A', '2A', '1A', 'CC')),
    quota_type      TEXT   NOT NULL CHECK (quota_type IN ('GENERAL', 'TATKAL')),

    -- Half-open [from_seq, to_seq), matching §5.2 and the INT4RANGE in V5.
    -- from < to is enforced here so an empty or inverted range cannot reach the
    -- allocator, where it would produce an all-zero mask that conflicts with
    -- nothing and appears to succeed against every berth.
    from_seq        SMALLINT NOT NULL CHECK (from_seq BETWEEN 0 AND 63),
    to_seq          SMALLINT NOT NULL CHECK (to_seq   BETWEEN 1 AND 64),
    CHECK (from_seq < to_seq),

    status          TEXT NOT NULL CHECK (status IN (
                        'HELD',              -- berths allocated, payment not started
                        'PAYMENT_PENDING',   -- payment initiated with the PSP
                        'CONFIRMED',         -- money captured, allocation durable
                        'CANCELLED',         -- cancelled after confirmation, refund per FR-44
                        'EXPIRED',           -- hold lapsed; also where FR-43 lands a cancelled HELD
                        'FAILED',            -- payment failed, nothing captured
                        'FAILED_REFUNDED')), -- money captured then returned (FR-24, FR-25)

    booking_class   TEXT NOT NULL CHECK (booking_class IN ('CNF', 'RAC', 'WL')),
    passenger_count SMALLINT NOT NULL CHECK (passenger_count BETWEEN 1 AND 6),

    -- Integer paise, never a floating point rupee amount. INV-7 recomputes the
    -- expected fare independently and compares; binary floating point makes
    -- that check fail at random on exactly representable-looking values.
    --
    -- Computed ONCE at hold time and frozen (FR-67). Never recomputed: a later
    -- edit to the rate table would otherwise retroactively break INV-7 across
    -- all history.
    fare_paise      BIGINT NOT NULL CHECK (fare_paise >= 0),

    user_id         BIGINT NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- DURABLE hold expiry. Deliberately NOT only in Redis.
    --
    -- Chaos scenario C2 runs `redis-cli FLUSHALL` during P2, concurrently with
    -- live payments. If expiry lived only in Redis, FR-24's "was the hold still
    -- valid when payment succeeded?" decision would become unanswerable exactly
    -- when it matters most, and B2's confirmation ordering could not be
    -- evaluated at all.
    hold_expires_at TIMESTAMPTZ,

    confirmed_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,

    -- FR-19 / DD-009: the same identity end-to-end. This IS the commandId that
    -- Strategy B's partition owner dedups on, not a separate notion.
    idempotency_key TEXT
);

CREATE TABLE passengers (
    id         BIGSERIAL PRIMARY KEY,
    booking_id BIGINT   NOT NULL REFERENCES bookings (id),
    name       TEXT     NOT NULL,
    age        SMALLINT NOT NULL CHECK (age BETWEEN 0 AND 120),
    gender     TEXT     NOT NULL CHECK (gender IN ('M', 'F', 'O')),

    -- NULL until a berth is assigned. RAC and WL passengers have no berth
    -- (FR-40 removed berth sharing), and WL may never get one.
    berth_id   BIGINT REFERENCES berths (id),
    coach_code TEXT
);

CREATE INDEX idx_bookings_schedule_status ON bookings (schedule_id, status);
CREATE INDEX idx_bookings_user            ON bookings (user_id);

-- Drives the hold reaper (§9.2) and FR-24's expiry decisions. Partial, because
-- only live holds are ever swept and the index should not carry the millions of
-- terminal bookings a soak accumulates.
CREATE INDEX idx_bookings_live_holds ON bookings (hold_expires_at)
    WHERE status IN ('HELD', 'PAYMENT_PENDING');

CREATE INDEX idx_passengers_booking ON passengers (booking_id);
