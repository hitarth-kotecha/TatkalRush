-- V1: extensions.
--
-- btree_gist is REQUIRED, not optional (SDD §8.4, §10.2).
--
-- The no_overlapping_allocations constraint in V5 needs a single GiST index
-- spanning three columns: schedule_id and berth_id compared with =, and
-- seg_range compared with &&. A plain B-tree cannot answer "do these ranges
-- overlap?", and stock GiST has no operator class for plain scalar equality.
-- btree_gist supplies the latter, letting both kinds of comparison share one
-- index. Without it, V5 fails with:
--
--   data type bigint has no default operator class for access method "gist"

CREATE EXTENSION IF NOT EXISTS btree_gist;
