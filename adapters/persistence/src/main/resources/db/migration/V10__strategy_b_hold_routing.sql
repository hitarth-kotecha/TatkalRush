-- V10: Strategy B's hold routing directory (§9.3, milestone 5).

-- ---------------------------------------------------------------------------
-- release(holdId) and confirm(holdId, bookingId) carry no pool - the port
-- gives them nothing else, because the caller (CancelBooking.release(),
-- ExpireHolds) often does not have one to hand either. Strategy A resolves
-- this from a Redis key (holdpool:) that any replica can read; Strategy B has
-- no equivalent, because its allocation state lives in one partition owner's
-- heap, reachable only by routing a Kafka message to the right partition -
-- and computing that route needs the pool BEFORE the message can be sent.
--
-- Milestone 1 shipped a per-JVM map for this, correct only when the same
-- replica that created a hold also releases or confirms it - true for many
-- requests but not guaranteed for any of them, since a stateless multi-replica
-- deployment does not send a booking's whole lifecycle to one replica. FR-43's
-- cancel and FR-18's reaper's `expireLapsed` in particular are dispatched
-- independently of which replica ran HoldSeats.
--
-- This table is the fix: a durable, cross-replica index. It is deliberately
-- NOT on the allocate hot path's synchronous return - the owner writes it
-- off the consumer thread, same as a checkpoint (DD-013), and a caller only
-- ever queries it on a LOCAL cache miss, which is the uncommon (cross-replica)
-- case. The common (same-replica) case never touches Postgres at all.
-- ---------------------------------------------------------------------------
CREATE TABLE hold_routing (
    hold_id    TEXT PRIMARY KEY,
    pool_key   TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE hold_routing IS
    'Strategy B only (§9.3, milestone 5): holdId -> pool key suffix, for '
    'routing release()/confirm() to the right Kafka partition when the '
    'calling replica did not create the hold. Deleted once the hold resolves '
    '(released or confirmed) - a row surviving past that is cleaned up '
    'lazily, not on a schedule, so a missed delete is a harmless leftover '
    'row rather than a correctness problem.';
