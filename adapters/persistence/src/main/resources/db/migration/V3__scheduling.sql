-- V3: schedules, quota pools and users (SDD §10.1).
--
-- A schedule is one train on one date. Quota pools are the unit of SELLABLE
-- inventory: (schedule, travel_class, quota_type). GENERAL and TATKAL are
-- separate pools over the SAME physical berths, which is why availability
-- differs by pool (FR-10) and why FR-15's cache key needed {pool} added -
-- without it, one pool's answer is served for the other's question for 2 s.

CREATE TABLE users (
    id           BIGSERIAL PRIMARY KEY,
    external_ref TEXT        NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE users IS
    'Synthetic only (NG-2, FR-69). At least 5,000 rows: FR-60 caps each user at '
    '10 rps, and one k6 VU maps to one distinct user, so an under-provisioned '
    'user table makes the harness rate-limit itself and voids the run (SDD 19.5).';

CREATE TABLE schedules (
    id                BIGSERIAL PRIMARY KEY,
    train_id          BIGINT NOT NULL REFERENCES trains (id),
    journey_date      DATE   NOT NULL,
    status            TEXT   NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'CHARTED', 'DEPARTED', 'CANCELLED')),
    chart_prepared_at TIMESTAMPTZ,

    -- Departure instant, needed by FR-44's refund tiers, which are relative to
    -- departure time. INV-7 recomputes refunds from (distance, class, quota,
    -- cancelled_at, departure_time) independently of anything stored.
    departure_at      TIMESTAMPTZ NOT NULL,

    UNIQUE (train_id, journey_date),

    -- A chart is prepared exactly once, and only in the CHARTED state.
    CHECK ((status = 'CHARTED') = (chart_prepared_at IS NOT NULL))
);

CREATE TABLE quota_pools (
    id           BIGSERIAL PRIMARY KEY,
    schedule_id  BIGINT   NOT NULL REFERENCES schedules (id),
    travel_class TEXT     NOT NULL CHECK (travel_class IN ('SL', '3A', '2A', '1A', 'CC')),

    -- Only these two exist (NG-3). No concession, senior, ladies or defence quotas.
    quota_type   TEXT     NOT NULL CHECK (quota_type IN ('GENERAL', 'TATKAL')),

    total_berths SMALLINT NOT NULL CHECK (total_berths >= 0),

    -- FR-28..FR-31: the Tatkal window. Unlock is a PURE FUNCTION OF CLOCK TIME
    -- evaluated per request (FR-30) - there is no scheduled job that "opens" a
    -- pool, because a job introduces a moment where the pool's state depends on
    -- whether the job ran. NULL means always open, which is how GENERAL pools
    -- are represented.
    opens_at     TIMESTAMPTZ,

    UNIQUE (schedule_id, travel_class, quota_type)
);

-- Which physical berths belong to which pool. This is where FR-48's "roughly
-- 300k berths" actually lives: ~11.5k physical berths x 30 forward days of
-- schedules. See DD on seed data shape.
CREATE TABLE pool_berths (
    pool_id  BIGINT NOT NULL REFERENCES quota_pools (id),
    berth_id BIGINT NOT NULL REFERENCES berths (id),

    -- Position of this berth within its pool, 0-based and contiguous. The
    -- allocation algorithm addresses berths by their index in the pool's mask
    -- array (Appendix A walks "pool.berths ordered by ordinal"), so this is the
    -- bridge between a database row and a bit position in Redis.
    pool_ordinal SMALLINT NOT NULL CHECK (pool_ordinal >= 0),

    PRIMARY KEY (pool_id, berth_id),
    UNIQUE (pool_id, pool_ordinal)
);

CREATE INDEX idx_schedules_train_date  ON schedules (train_id, journey_date);
CREATE INDEX idx_schedules_departure   ON schedules (departure_at) WHERE status = 'OPEN';
CREATE INDEX idx_quota_pools_schedule  ON quota_pools (schedule_id, travel_class, quota_type);
CREATE INDEX idx_pool_berths_berth     ON pool_berths (berth_id);
