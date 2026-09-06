-- FR-60: a two-bucket sliding window, 10 requests per second per user.
--
-- WHY TWO BUCKETS. A fixed window - count per second, reset at the boundary -
-- lets a client send its whole allowance at 0.999 s and its next whole allowance
-- at 1.001 s: twenty requests in two milliseconds, and the limiter never notices.
-- That boundary is exactly where a retry storm lands, because every client's
-- clock ticks together.
--
-- So the rate is ESTIMATED across the boundary:
--
--     estimate = current + previous * (1 - elapsed_fraction)
--
-- A quarter second into a bucket, the previous second still counts for 75%.
--
-- This is an APPROXIMATION, and worth saying so rather than implying exactness:
-- it assumes the previous bucket's requests were spread evenly across that
-- second. A client that sent everything in the last 10 ms of the previous bucket
-- is under-counted; one that sent everything in the first 10 ms is over-counted.
-- The error is bounded by the limit itself, which for a 10 rps cap on a system
-- whose benchmarks must never trip it at all is comfortably good enough.
--
-- WHY LUA. Read both counters, weigh them, compare, increment, expire - as one
-- indivisible step. Done as separate commands, two concurrent requests both read
-- the same count and both decide they are under the limit.
--
-- KEYS[1] current bucket   KEYS[2] previous bucket
--   Both carry the user id inside braces (rate:{7}:1790000123), which is Redis
--   Cluster hash-tag syntax: the slot is computed from the braced part alone, so
--   a user's two buckets always land on the same node. A multi-key script
--   requires that, and without the braces this would work on a single node and
--   fail the day anyone shards it.
--
-- ARGV[1] limit   ARGV[2] elapsed fraction (0..1)   ARGV[3] ttl seconds
--
-- Returns {allowed, remaining} where allowed is 1 or 0.

local current_key  = KEYS[1]
local previous_key = KEYS[2]

local limit   = tonumber(ARGV[1])
local elapsed = tonumber(ARGV[2])
local ttl     = tonumber(ARGV[3])

local current  = tonumber(redis.call('GET', current_key))  or 0
local previous = tonumber(redis.call('GET', previous_key)) or 0

local estimate = current + previous * (1 - elapsed)

if estimate >= limit then
  -- NOT incremented on rejection. Counting rejected requests would let a client
  -- that keeps hammering hold its own window open indefinitely - the limiter
  -- would punish the retry rather than the traffic, and a caller that backed off
  -- correctly would be indistinguishable from one that did not.
  return {0, 0}
end

local after = redis.call('INCR', current_key)

-- Refreshed every time rather than set once. A bucket written at the very start
-- of its second and then used throughout it would otherwise expire while still
-- being the CURRENT bucket, and the window would silently reset mid-second.
redis.call('EXPIRE', current_key, ttl)

local remaining = limit - (after + previous * (1 - elapsed))
if remaining < 0 then remaining = 0 end

return {1, math.floor(remaining)}
