-- V6: payments, refunds and the ledger (SDD §10.3).

CREATE TABLE payments (
    id             BIGSERIAL PRIMARY KEY,
    booking_id     BIGINT NOT NULL REFERENCES bookings (id),

    -- The PSP's identifier, UNIQUE so a replayed webhook cannot create a second
    -- payment row for the same charge.
    psp_payment_id TEXT   NOT NULL UNIQUE,

    amount_paise   BIGINT NOT NULL CHECK (amount_paise > 0),
    status         TEXT   NOT NULL
        CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'REFUNDED')),
    initiated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at     TIMESTAMPTZ
);

-- Webhook idempotency (FR-61 and §13). A PSP may deliver the same event twice,
-- out of order, or never. UNIQUE(psp_payment_id, event_type) makes a duplicate
-- delivery a no-op insert conflict rather than a second state transition -
-- which, for a SUCCESS event, would be a double capture.
CREATE TABLE payment_events (
    id             BIGSERIAL PRIMARY KEY,
    psp_payment_id TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (psp_payment_id, event_type)
);

CREATE TABLE refunds (
    id           BIGSERIAL PRIMARY KEY,
    booking_id   BIGINT NOT NULL REFERENCES bookings (id),
    payment_id   BIGINT NOT NULL REFERENCES payments (id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),

    -- The reason column is a diagnostic instrument, not a label.
    --
    --   CANCELLED         - user cancelled a confirmed booking (FR-44)
    --   CHART_WL_REFUND   - still waitlisted at chart time (FR-42)
    --   HOLD_EXPIRED      - BENIGN. Payment succeeded after the hold lapsed
    --                       (FR-24). Expected during chaos C2 and C5.
    --   ALLOCATION_CONFLICT - A BUG. The EXCLUDE constraint tripped against a
    --                       LIVE hold at confirm time (FR-25, DD-008). INV-11
    --                       asserts zero of these; a single one fails the run.
    --
    -- Separating the last two is the whole point: without it, an allocator
    -- defect is indistinguishable from an ordinary expiry race, and the most
    -- serious bug the system can have hides inside its most routine event.
    reason       TEXT NOT NULL CHECK (reason IN (
                     'CANCELLED', 'CHART_WL_REFUND', 'HOLD_EXPIRED', 'ALLOCATION_CONFLICT')),

    status       TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Double-entry-ish record of money movement, so INV-2 (no double charges) and
-- INV-3 (no orphaned payments) can be checked against something other than the
-- payment table's own opinion of itself.
CREATE TABLE ledger_entries (
    id           BIGSERIAL PRIMARY KEY,
    booking_id   BIGINT NOT NULL REFERENCES bookings (id),
    entry_type   TEXT   NOT NULL CHECK (entry_type IN ('CHARGE', 'REFUND')),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_booking       ON payments (booking_id);
CREATE INDEX idx_payments_status        ON payments (status) WHERE status = 'INITIATED';
CREATE INDEX idx_refunds_booking        ON refunds (booking_id);
CREATE INDEX idx_ledger_booking         ON ledger_entries (booking_id);

-- INV-11 asks "does any ALLOCATION_CONFLICT refund exist?" after every run.
-- Partial index so that question is answered by an index scan over an
-- ordinarily empty set rather than a sequential scan of every refund from a
-- 30-minute soak.
CREATE INDEX idx_refunds_allocation_conflict ON refunds (created_at)
    WHERE reason = 'ALLOCATION_CONFLICT';
