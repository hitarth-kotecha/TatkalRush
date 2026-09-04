-- V2: physical railway reference data (SDD §10.1).
--
-- These tables describe the network and rolling stock: what exists in the world,
-- independent of any journey date. Berths here are PHYSICAL - one row per berth
-- per coach, roughly 11.5k rows for the 20 seeded trains. Bookable inventory is
-- (schedule x class x quota), which lives in V3's pool_berths and is where the
-- ~300k figure in FR-48 actually appears.

CREATE TABLE stations (
    id   BIGSERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL
);

CREATE TABLE trains (
    id                BIGSERIAL PRIMARY KEY,
    number            TEXT   NOT NULL UNIQUE,
    name              TEXT   NOT NULL,
    origin_station_id BIGINT NOT NULL REFERENCES stations (id),
    dest_station_id   BIGINT NOT NULL REFERENCES stations (id),
    -- FR-49: three trains receive disproportionate load in profile P3, the
    -- hot-partition profile that discriminates between the two strategies.
    is_hot            BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE train_stops (
    id          BIGSERIAL PRIMARY KEY,
    train_id    BIGINT   NOT NULL REFERENCES trains (id),

    station_id  BIGINT   NOT NULL REFERENCES stations (id),

    -- Position along the route, 0-based. This is the SEGMENT INDEX that every
    -- mask operation is expressed in (§5.2, Appendix B): a journey from stop i
    -- to stop j occupies segments [i, j).
    --
    -- Capped at 63 because a berth's occupancy mask is a single 64-bit integer,
    -- and Lua 5.1 inside Redis has no native 64-bit integer type (FR-3a, DD-002).
    -- A route longer than this does not degrade - it silently corrupts the mask.
    seq         SMALLINT NOT NULL CHECK (seq BETWEEN 0 AND 63),

    arr_time    TIME,
    dep_time    TIME,

    -- Cumulative distance from the train's origin. NUMERIC, never floating
    -- point: FR-67 computes fare as ceil(distance x rate) and INV-7 recomputes
    -- it independently to compare against the stored value. Binary floating
    -- point would make that comparison fail intermittently on exact multiples.
    --
    -- Must be monotonically increasing per train. Not expressible as a row-level
    -- CHECK; the seed generator guarantees it (FR-48) and a non-monotonic value
    -- would produce a negative fare, so it is worth an invariant later.
    distance_km NUMERIC(7, 2) NOT NULL CHECK (distance_km >= 0),

    UNIQUE (train_id, seq),
    UNIQUE (train_id, station_id)
);

CREATE TABLE coaches (
    id           BIGSERIAL PRIMARY KEY,
    train_id     BIGINT   NOT NULL REFERENCES trains (id),
    code         TEXT     NOT NULL,
    -- Only GENERAL and TATKAL quotas exist (NG-3); travel class is separate
    -- from quota. Modelled as a CHECK rather than a Postgres ENUM: adding a
    -- value to an ENUM is a schema-wide ALTER TYPE, whereas a CHECK is an
    -- ordinary migration that Flyway can version like anything else.
    travel_class TEXT     NOT NULL CHECK (travel_class IN ('SL', '3A', '2A', '1A', 'CC')),
    berth_count  SMALLINT NOT NULL CHECK (berth_count > 0),

    UNIQUE (train_id, code)
);

CREATE TABLE berths (
    id        BIGSERIAL PRIMARY KEY,
    coach_id  BIGINT   NOT NULL REFERENCES coaches (id),

    -- Position within the coach. Allocation walks berths "ordered by ordinal"
    -- (Appendix A), so this ordering is part of the algorithm's observable
    -- behaviour: T-7 asserts the Java and Lua implementations choose the SAME
    -- berth, not merely an equally valid one.
    ordinal   SMALLINT NOT NULL CHECK (ordinal >= 0),

    -- FR-48. Needed by FR-38, whose RAC allowance is 2 x side_lower_berth_count
    -- and is not computable without a berth-type distribution.
    berth_type TEXT NOT NULL
        CHECK (berth_type IN ('LOWER', 'MIDDLE', 'UPPER', 'SIDE_LOWER', 'SIDE_UPPER')),

    UNIQUE (coach_id, ordinal)
);

CREATE INDEX idx_train_stops_train_seq ON train_stops (train_id, seq);
CREATE INDEX idx_coaches_train         ON coaches (train_id);
CREATE INDEX idx_berths_coach          ON berths (coach_id, ordinal);
