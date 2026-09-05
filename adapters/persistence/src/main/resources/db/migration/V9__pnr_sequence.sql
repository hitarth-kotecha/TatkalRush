-- V9: the PNR sequence (FR-26).
--
-- FR-26 forbids random generation with collision retry, and the reason is this
-- project's subject. Random-with-retry degrades exactly under the load a Tatkal
-- spike creates: as the space fills, collisions rise, and the retry storm peaks
-- at the moment the system is already at its limit. A sequence is O(1) forever
-- and the uniqueness comes from Postgres rather than from hope.
--
-- MAXVALUE matches Pnr.MAX_SEQUENCE: nine digits plus a Luhn check digit is ten.
-- NO CYCLE so exhaustion is an error rather than a silent reissue - a wrapped
-- value would collide with a PNR that already belongs to someone, and the unique
-- index would then fail a confirmation for a customer whose money is already
-- captured. At ~700 berths a train and 600 schedules, this bound is not
-- reachable by any run this project performs.
--
-- CACHE 1 deliberately. Postgres caches sequence values PER SESSION, and a
-- pooled connection holding a block of 50 would leave gaps of 50 whenever that
-- connection is returned to the pool - which under a spike is constantly. Gaps
-- are not a correctness problem (nextval is never rolled back, so gaps exist
-- regardless), but "PNRs jump by thousands on the busiest trains" is a question
-- with no good answer, and the sequence is nowhere near being a bottleneck.

CREATE SEQUENCE pnr_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 999999999
    CACHE 1
    NO CYCLE;

COMMENT ON SEQUENCE pnr_seq IS
    'FR-26: PNRs are derived from this plus a Luhn check digit. Never random. '
    'Gaps are expected - nextval is not rolled back, deliberately, because '
    'rolling it back would serialise every writer on the sequence.';
