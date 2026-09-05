-- Frees the berths of a CONFIRMED booking (FR-43).
--
-- Distinct from release.lua, which works from a hold record. confirm.lua deletes
-- that record on purpose - so the reaper can never sweep a berth someone paid
-- for - and with it goes the only handle release.lua uses. After confirmation the
-- bits are set with nothing pointing at them, so the caller must say which berths
-- it owns, reading them from the booking row.
--
-- IDEMPOTENT, and that is where the care goes.
--
-- release.lua may add `freed` to every occupied segment's count, because it
-- created the hold and knows every one of those berths carried every bit of the
-- range. Nothing guarantees that here: this runs from a user cancellation, a
-- retry of one, and a chart-time sweep, and any of them can arrive twice. A bit
-- that is already clear must contribute NOTHING to the free count.
--
-- So this counts per (segment, berth) - testing each bit before clearing it -
-- rather than multiplying a berth count by a segment. Getting that wrong inflates
-- freecount: the pool reports berths it does not have, INV-12 fails at the next
-- quiesce point, and the number it corrupts sits directly upstream of the metric
-- 9.4's conclusion rests on.
--
-- KEYS[1] masks   KEYS[2] holds   KEYS[3] freecount   KEYS[4] holddetail
--   The holds keys are unused here and passed anyway, so every script in this
--   directory takes the same KEYS vector. Under Redis Cluster the whole vector
--   also has to hash to one slot, and a script with its own shorter layout is a
--   script that quietly stops matching when that constraint arrives.
-- ARGV[1] fromSeq ARGV[2] toSeq   ARGV[3] segmentCount
-- ARGV[4..] berth ordinals
--
-- Returns the number of (berth, segment) bits actually cleared. Zero is a
-- successful no-op.

local masks_key = KEYS[1]
local free_key  = KEYS[3]

local from_seq = tonumber(ARGV[1])
local to_seq   = tonumber(ARGV[2])
local segments = tonumber(ARGV[3])

local TWO_32 = 4294967296
local function u32(n)
  n = n % TWO_32
  if n < 0 then n = n + TWO_32 end
  return n
end

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
  -- struct.unpack returns UNSIGNED; bit.band returns SIGNED. Every value that
  -- leaves a bit operation goes back through u32 before being packed, or a
  -- negative appears where a 32-bit half belongs (DD-002).
  local lo, hi = struct.unpack('<I4I4', masks, ordinal * 8 + 1)
  mask_lo[ordinal] = lo
  mask_hi[ordinal] = hi
end

-- The ordinals to free, from ARGV[4] onwards.
local ordinals = {}
for i = 4, #ARGV do
  local o = tonumber(ARGV[i])
  if o < 0 or o >= berth_count then
    return redis.error_reply(
      'berth ordinal ' .. o .. ' is outside pool ' .. masks_key
      .. ' (' .. berth_count .. ' berths)')
  end
  ordinals[#ordinals + 1] = o
end

-- Count first, against the state as it is. Per segment, per berth, because an
-- already-clear bit must add nothing.
local free_parts = {}
local cleared = 0

for seg = 0, segments - 1 do
  local count = struct.unpack('<I4', free, seg * 4 + 1)

  if seg >= from_seq and seg < to_seq then
    local seg_bit, is_high
    if seg < 32 then
      seg_bit = bit.lshift(1, seg)
      is_high = false
    else
      seg_bit = bit.lshift(1, seg - 32)
      is_high = true
    end

    local freed_here = 0
    for _, o in ipairs(ordinals) do
      local half = is_high and mask_hi[o] or mask_lo[o]
      if bit.band(half, seg_bit) ~= 0 then
        freed_here = freed_here + 1
      end
    end

    count = count + freed_here
    cleared = cleared + freed_here
  end

  free_parts[seg + 1] = struct.pack('<I4', count)
end

-- Then clear. AND NOT touches only this booking's segments; another booking on a
-- different leg of the same berth is untouched, which is the whole of what
-- segment-wise inventory buys.
local clear_lo, clear_hi = 0, 0
for seg = from_seq, to_seq - 1 do
  if seg < 32 then
    clear_lo = bit.bor(clear_lo, bit.lshift(1, seg))
  else
    clear_hi = bit.bor(clear_hi, bit.lshift(1, seg - 32))
  end
end

for _, o in ipairs(ordinals) do
  mask_lo[o] = u32(bit.band(mask_lo[o], bit.bnot(clear_lo)))
  mask_hi[o] = u32(bit.band(mask_hi[o], bit.bnot(clear_hi)))
end

local parts = {}
for ordinal = 0, berth_count - 1 do
  parts[ordinal + 1] = struct.pack('<I4I4', mask_lo[ordinal], mask_hi[ordinal])
end

-- Masks and free counts written in the same script. Writing one without the
-- other is precisely the drift DD-012 and INV-12 exist to catch.
redis.call('SET', masks_key, table.concat(parts))
redis.call('SET', free_key, table.concat(free_parts))

return cleared
