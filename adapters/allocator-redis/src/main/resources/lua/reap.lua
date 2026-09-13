-- Strategy A: the BACKGROUND reap of one pool (SDD §13.2).
--
-- WHAT THIS IS NOT. It is not what keeps the system correct. allocate.lua reaps
-- expired holds itself, before every scan (§9.2), so a pool that anyone is
-- booking from never keeps a lapsed berth - and a reaper that is stalled, crash-
-- looping or simply not deployed cannot lose a seat. This script exists for the
-- pool NOBODY is booking from: after a Tatkal spike moves on, its expired holds
-- would otherwise stay set in the masks indefinitely, search would under-report
-- the pool, and no quiesced invariant check could ever run.
--
-- WHY IT DUPLICATES allocate.lua's REAP LOOP. Lua has no shared modules under
-- EVALSHA, and the one way to reuse the loop without copying it - calling
-- allocate with a request that cannot fit - depends on allocate's early exits
-- staying exactly as they are. So the loop is copied, and the copy is held to the
-- same specification as the original: T-7 compares this script against
-- BerthPool.reapExpired after every step, exactly as it compares allocate.lua's
-- lazy reap. Two Lua loops, one Java reference, no drift that survives the build.
--
-- KEYS[1] masks   KEYS[2] holds   KEYS[3] freecount   KEYS[4] holddetail
-- ARGV[1] nowMs
--
-- Returns the number of holds removed from the ZSET.

local masks_key  = KEYS[1]
local holds_key  = KEYS[2]
local free_key   = KEYS[3]
local detail_key = KEYS[4]
local now_ms     = tonumber(ARGV[1])

-- The cheap exit, and the common case. The sweep calls this for every pool that
-- has ANY hold; most of those holds are live. Checking for one expired entry
-- before loading two blobs keeps a sweep over thousands of pools from copying
-- thousands of mask arrays to find nothing.
local expired = redis.call('ZRANGEBYSCORE', holds_key, '-inf', now_ms)
if #expired == 0 then
  return 0
end

local masks = redis.call('GET', masks_key)
local free  = redis.call('GET', free_key)
if not masks or not free then
  -- Holds with no pool state: the pool was flushed (chaos C2) and not yet
  -- rebuilt. Nothing to release into. §13.4's rebuild replays from Postgres,
  -- which knows nothing of unconfirmed holds, so dropping them is what the
  -- rebuild would do anyway.
  for i = 1, #expired do
    redis.call('HDEL', detail_key, expired[i])
    redis.call('ZREM', holds_key, expired[i])
  end
  return #expired
end

-- The segment count comes from the pool itself. The reaper discovers pools from
-- key names and has no view of their shape to validate, unlike allocate.lua's
-- caller - so there is nothing to compare against, only something to read.
local segments    = #free / 4
local berth_count = #masks / 8

local TWO_32 = 4294967296
local function u32(n)
  n = n % TWO_32
  if n < 0 then n = n + TWO_32 end
  return n
end

local mask_lo, mask_hi = {}, {}
for ordinal = 0, berth_count - 1 do
  local lo, hi = struct.unpack('<I4I4', masks, ordinal * 8 + 1)
  mask_lo[ordinal] = lo
  mask_hi[ordinal] = hi
end

local free_counts = {}
for seg = 0, segments - 1 do
  free_counts[seg] = (struct.unpack('<I4', free, seg * 4 + 1))
end

local function segments_in_mask(lo, hi, count)
  local list = {}
  for seg = 0, count - 1 do
    local hit
    if seg < 32 then
      hit = bit.band(lo, bit.lshift(1, seg)) ~= 0
    else
      hit = bit.band(hi, bit.lshift(1, seg - 32)) ~= 0
    end
    if hit then list[#list + 1] = seg end
  end
  return list
end

-- ---------------------------------------------- the loop, as in allocate.lua

local reaped_any = false
for i = 1, #expired do
  local dead_id = expired[i]
  local detail = redis.call('HGET', detail_key, dead_id)
  if detail then
    local d_lo, d_hi, ords = string.match(detail, '^(%d+):(%d+):(.*)$')
    d_lo, d_hi = tonumber(d_lo), tonumber(d_hi)

    local freed = 0
    for ord in string.gmatch(ords, '(%d+)') do
      local o = tonumber(ord)
      -- AND NOT: clear only this hold's bits. A complementary booking on the same
      -- berth (T-3) keeps its own.
      mask_lo[o] = u32(bit.band(mask_lo[o], bit.bnot(d_lo)))
      mask_hi[o] = u32(bit.band(mask_hi[o], bit.bnot(d_hi)))
      freed = freed + 1
    end

    for _, seg in ipairs(segments_in_mask(d_lo, d_hi, segments)) do
      free_counts[seg] = free_counts[seg] + freed
    end

    redis.call('HDEL', detail_key, dead_id)
    reaped_any = true
  end
  redis.call('ZREM', holds_key, dead_id)
end

if reaped_any then
  local mask_parts = {}
  for ordinal = 0, berth_count - 1 do
    mask_parts[ordinal + 1] = struct.pack('<I4I4', mask_lo[ordinal], mask_hi[ordinal])
  end
  redis.call('SET', masks_key, table.concat(mask_parts))

  local free_parts = {}
  for seg = 0, segments - 1 do
    free_parts[seg + 1] = struct.pack('<I4', free_counts[seg])
  end
  redis.call('SET', free_key, table.concat(free_parts))
end

return #expired
