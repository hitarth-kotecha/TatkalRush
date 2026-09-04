-- Strategy A: atomic segment-wise berth allocation (SDD §9.2, Appendix A).
--
-- WHY THIS EXISTS AT ALL. Redis executes Lua single-threaded, so the whole
-- read-modify-write below cannot interleave with another allocation. That is the
-- entire concurrency story for Strategy A: no locks, no CAS retry loop, no
-- optimistic versioning. The atomicity is a property of where the code runs.
--
-- WHY IT IS NOT A CALL INTO domain/inventory. It cannot be. This executes inside
-- the Redis process, and the atomicity above depends on the algorithm never
-- leaving Redis mid-execution. So the algorithm is specified once and implemented
-- twice, and equivalence is a TESTED property (T-7), not a structural one
-- (DD-001).
--
-- ============================================================================
-- FR-3a: THERE ARE NO 64-BIT INTEGERS HERE
-- ============================================================================
-- Redis embeds Lua 5.1. Every number is a double, exact only to 2^53. A 64-bit
-- mask handled arithmetically is silently rounded above that, and a rounded mask
-- is a WRONG AVAILABILITY ANSWER with no error raised.
--
-- So every mask is two 32-bit halves and every operation goes through the `bit`
-- library. Two behaviours, verified against redis 7.4.11 before this was written:
--
--   struct.unpack('<I4')  returns UNSIGNED   ->  2147483648
--   bit.band(hi, hi)      returns SIGNED     -> -2147483648
--
-- Those are the same 32 bits and different numbers. Comparing a value that came
-- from unpack against one that came from a bit op is wrong for any word with bit
-- 31 set - which is segment 63, exactly the boundary FR-3a demands be tested.
-- Everything stored back is normalised through u32() for that reason.
--
-- KEYS[1] masks      binary, berth_count * 8 bytes, little-endian (lo, hi) pairs
-- KEYS[2] holds      ZSET, holdId -> expiry epoch ms
-- KEYS[3] freecount  binary, segment_count * 4 bytes, little-endian uint32
-- KEYS[4] holddetail HASH, holdId -> "lo:hi:ord,ord,..."
--
-- ARGV[1] requestMaskLo   ARGV[2] requestMaskHi   ARGV[3] passengerCount
-- ARGV[4] holdId          ARGV[5] ttlMs           ARGV[6] nowMs
-- ARGV[7] segmentCount
--
-- Returns {"OK", ordinal, ...} or {"UNAVAILABLE", availableCount}

local masks_key   = KEYS[1]
local holds_key   = KEYS[2]
local free_key    = KEYS[3]
local detail_key  = KEYS[4]

local req_lo      = tonumber(ARGV[1])
local req_hi      = tonumber(ARGV[2])
local passengers  = tonumber(ARGV[3])
local hold_id     = ARGV[4]
local ttl_ms      = tonumber(ARGV[5])
local now_ms      = tonumber(ARGV[6])
local segments    = tonumber(ARGV[7])

-- ---------------------------------------------------------------- helpers

local TWO_32 = 4294967296

-- Normalises a signed bit-op result back into the unsigned range struct expects.
-- Without this, a mask with segment 63 set round-trips as a different number even
-- though the bits are identical.
local function u32(n)
  n = n % TWO_32
  if n < 0 then n = n + TWO_32 end
  return n
end

local function read_mask(blob, ordinal)
  local offset = ordinal * 8 + 1
  local lo, hi = struct.unpack('<I4I4', blob, offset)
  return lo, hi
end

local function read_free(blob, segment)
  return (struct.unpack('<I4', blob, segment * 4 + 1))
end

-- Segments covered by the request mask, as an explicit list. Built once and
-- reused: the free-count updates walk it for every hold reaped and once for the
-- allocation itself.
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

-- ------------------------------------------------------------ load state

local masks = redis.call('GET', masks_key)
if not masks then
  return redis.error_reply('pool not initialised: ' .. masks_key)
end

local free = redis.call('GET', free_key)
if not free then
  return redis.error_reply('free counts not initialised: ' .. free_key)
end

local berth_count = #masks / 8

-- Validate the caller's view of the pool against the pool itself.
--
-- Without this, a segment count that disagrees with the provisioned pool reads
-- past the end of the free-count blob and surfaces as
-- "bad argument #2 to 'unpack' (data string too short)" - a message that says
-- nothing about which of the two is wrong. Found while testing this script by
-- hand, from a mismatch that a route changing length would reproduce exactly.
if #masks % 8 ~= 0 then
  return redis.error_reply(
    'corrupt mask blob for ' .. masks_key .. ': ' .. #masks .. ' bytes is not a multiple of 8')
end
if #free ~= segments * 4 then
  return redis.error_reply(
    'segment count mismatch for ' .. masks_key .. ': pool has ' .. (#free / 4)
    .. ' segments, request says ' .. segments)
end

-- Mutable copies as arrays of numbers; packed back once at the end. Rebuilding
-- the whole blob per mutation would be O(berths) string churn on the hot path of
-- every attempt during a spike.
local mask_lo, mask_hi = {}, {}
for ordinal = 0, berth_count - 1 do
  local lo, hi = read_mask(masks, ordinal)
  mask_lo[ordinal] = lo
  mask_hi[ordinal] = hi
end

local free_counts = {}
for seg = 0, segments - 1 do
  free_counts[seg] = read_free(free, seg)
end

-- --------------------------------------------------- 1. reap expired holds
--
-- Reaping INSIDE the allocation script is the key design decision of §9.2. It
-- makes expiry lazy and self-healing: a stalled background reaper cannot cause
-- seats to be permanently lost, because the next allocation on this pool
-- reclaims them. The background reaper (§13.2) exists only so idle pools release
-- seats too; correctness does not depend on it running.

local expired = redis.call('ZRANGEBYSCORE', holds_key, '-inf', now_ms)
for i = 1, #expired do
  local dead_id = expired[i]
  local detail = redis.call('HGET', detail_key, dead_id)
  if detail then
    local d_lo, d_hi, ords = string.match(detail, '^(%d+):(%d+):(.*)$')
    d_lo, d_hi = tonumber(d_lo), tonumber(d_hi)

    local freed = 0
    for ord in string.gmatch(ords, '(%d+)') do
      local o = tonumber(ord)
      -- Release is AND NOT: clear only this hold's bits, leaving any other
      -- booking on the same berth untouched.
      mask_lo[o] = u32(bit.band(mask_lo[o], bit.bnot(d_lo)))
      mask_hi[o] = u32(bit.band(mask_hi[o], bit.bnot(d_hi)))
      freed = freed + 1
    end

    for _, seg in ipairs(segments_in_mask(d_lo, d_hi, segments)) do
      free_counts[seg] = free_counts[seg] + freed
    end

    redis.call('HDEL', detail_key, dead_id)
  end
  redis.call('ZREM', holds_key, dead_id)
end

-- ------------------------------------------------------ 2. find first fit
--
-- FR-5: lowest ordinal that satisfies FR-1, scanning in order. Determinism is not
-- tidiness here - T-7 asserts this picks the SAME berths as the Java reference,
-- not merely an equally valid set.

local chosen = {}
for ordinal = 0, berth_count - 1 do
  if bit.band(mask_lo[ordinal], req_lo) == 0
      and bit.band(mask_hi[ordinal], req_hi) == 0 then
    chosen[#chosen + 1] = ordinal
    if #chosen == passengers then break end
  end
end

-- --------------------------------------------------------- 3. all or nothing
--
-- FR-6. A partial allocation would leave berths held for a booking that failed -
-- the orphaned hold §1 claims this system does not produce. Note that the reaping
-- above has already been applied to the in-memory copies but NOT written back;
-- returning here discards it, which is safe because reaping is idempotent and the
-- next call redoes it.

if #chosen < passengers then
  return { 'UNAVAILABLE', #chosen }
end

-- ------------------------------------------------------------ 4. allocate

for i = 1, #chosen do
  local o = chosen[i]
  mask_lo[o] = u32(bit.bor(mask_lo[o], req_lo))
  mask_hi[o] = u32(bit.bor(mask_hi[o], req_hi))
end

for _, seg in ipairs(segments_in_mask(req_lo, req_hi, segments)) do
  free_counts[seg] = free_counts[seg] - passengers
end

-- ------------------------------------------------------- 5. persist state

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

redis.call('ZADD', holds_key, now_ms + ttl_ms, hold_id)
redis.call('HSET', detail_key, hold_id,
           req_lo .. ':' .. req_hi .. ':' .. table.concat(chosen, ','))

-- ---------------------------------------------------------------- 6. reply

local reply = { 'OK' }
for i = 1, #chosen do
  reply[#reply + 1] = chosen[i]
end
return reply
