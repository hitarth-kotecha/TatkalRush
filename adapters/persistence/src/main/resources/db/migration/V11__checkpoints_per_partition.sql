-- V11: checkpoints learn which Kafka partition they belong to (DD-049's gap).
--
-- Until now a checkpoint was written and never read: replay always started at
-- offset 0 because a newly-assigned partition owner had no way to know which
-- pools live on its partition, so it could not know which checkpoints to load.
-- Recovery now loads every checkpoint for its partition and replays only the
-- tail, which needs the partition on the row.
--
-- kafka_offset's meaning changes with this migration. It used to be "the last
-- event for THIS pool". It is now "this snapshot is complete through this offset
-- of the partition's events topic, whichever pool those events were for". The
-- difference matters for idle pools: under the old meaning a pool nobody booked
-- from for an hour had an hour-old offset, and recovery starting from the oldest
-- checkpoint in a partition would replay that whole hour.
--
-- A NEGATIVE kafka_offset (-1) is a presence marker, written synchronously when
-- a pool is provisioned: it proves the pool exists without claiming a state, and
-- forces the partition's recovery to fall back to a full replay until a real
-- snapshot replaces it. That is what lets recovery treat "this partition's
-- checkpoint rows" as the complete list of its pools.
--
-- Existing rows are discarded rather than backfilled. Nothing ever read them, a
-- row without a partition cannot be trusted as part of a complete set, and an
-- absent checkpoint only costs a full replay, which is always correct.
DELETE FROM checkpoints;

ALTER TABLE checkpoints ADD COLUMN kafka_partition INT NOT NULL;

CREATE INDEX idx_checkpoints_partition ON checkpoints (kafka_partition);

COMMENT ON COLUMN checkpoints.kafka_partition IS
    'The booking-events partition whose owner wrote this row. Recovery loads '
    'every row for its partition; the set must be complete, hence the marker rows.';
