-- Releases a hold's berths (§9.2).
--
-- Idempotent, and that is not politeness. Release arrives from hold expiry,
-- user cancellation and chart preparation, and the lazy reaper inside
-- allocate.lua can beat any of them to the same hold. "Already gone" is the
-- normal concurrent outcome, not a failure.
--
-- KEYS[1] masks   KEYS[2] holds   KEYS[3] freecount   KEYS[4] holddetail
-- ARGV[1] holdId  ARGV[2] segmentCount
--
-- Returns 1 if a live hold was released, 0 if it had already gone.

local masks_key  = KEYS[1]
local holds_key  = KEYS[2]
local free_key   = KEYS[3]
local detail_key = KEYS[4]

local hold_id  = ARGV[1]
local segments = tonumber(ARGV[2])

local TWO_32 = 4294967296
local function u32(n)
  n = n % TWO_32
  if n < 0 then n = n + TWO_32 end
  return n
end

local detail = redis.call('HGET', detail_key, hold_id)
if not detail then
  -- Clean up any ZSET entry that outlived its detail, then report "nothing to
  -- do". Leaving a dangling member would make the next reap try, and fail, on
  -- every subsequent allocation.
  redis.call('ZREM', holds_key, hold_id)
  return 0
end

local d_lo, d_hi, ords = string.match(detail, '^(%d+):(%d+):(.*)$')
d_lo, d_hi = tonumber(d_lo), tonumber(d_hi)

local masks = redis.call('GET', masks_key)
local free = redis.call('GET', free_key)
if not masks or not free then
  return redis.error_reply('pool not initialised: ' .. masks_key)
end
if #free ~= segments * 4 then
  return redis.error_reply(
    'segment count mismatch for ' .. masks_key .. ': pool has ' .. (#free / 4)
    .. ' segments, request says ' .. segments)
end

local berth_count = #masks / 8
local mask_lo, mask_hi = {}, {}
for ordinal = 0, berth_count - 1 do
  local lo, hi = struct.unpack('<I4I4', masks, ordinal * 8 + 1)
  mask_lo[ordinal] = lo
  mask_hi[ordinal] = hi
end

local freed = 0
for ord in string.gmatch(ords, '(%d+)') do
  local o = tonumber(ord)
  -- AND NOT: clears only this hold's bits. Another booking occupying different
  -- segments of the same berth is untouched, which is the whole point of
  -- segment-wise inventory.
  mask_lo[o] = u32(bit.band(mask_lo[o], bit.bnot(d_lo)))
  mask_hi[o] = u32(bit.band(mask_hi[o], bit.bnot(d_hi)))
  freed = freed + 1
end

local parts = {}
for ordinal = 0, berth_count - 1 do
  parts[ordinal + 1] = struct.pack('<I4I4', mask_lo[ordinal], mask_hi[ordinal])
end
redis.call('SET', masks_key, table.concat(parts))

-- Free counts restored in the same script as the masks. Restoring one without
-- the other is the drift DD-012 and INV-12 exist to catch.
local free_parts = {}
for seg = 0, segments - 1 do
  local count = struct.unpack('<I4', free, seg * 4 + 1)
  local occupied
  if seg < 32 then
    occupied = bit.band(d_lo, bit.lshift(1, seg)) ~= 0
  else
    occupied = bit.band(d_hi, bit.lshift(1, seg - 32)) ~= 0
  end
  if occupied then count = count + freed end
  free_parts[seg + 1] = struct.pack('<I4', count)
end
redis.call('SET', free_key, table.concat(free_parts))

redis.call('ZREM', holds_key, hold_id)
redis.call('HDEL', detail_key, hold_id)
return 1
