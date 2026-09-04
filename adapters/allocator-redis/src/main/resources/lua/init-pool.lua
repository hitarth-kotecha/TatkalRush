-- Provisions (or rebuilds) one quota pool's Redis state (§9.2, §13.4).
--
-- Used in two situations that must behave identically:
--
--   1. Schedule creation, seeding an empty pool.
--   2. Recovery after chaos scenario C2 (`redis-cli FLUSHALL` mid-run), where
--      masks AND free counts are rebuilt from Postgres.
--
-- The second is why free counts are seeded HERE, in the same script that seeds
-- masks, rather than anywhere else. v1.1 rebuilt only the masks: after C2 the
-- masks returned and the counts did not, so search reported zero availability for
-- the remainder of the run while INV-8 passed happily. Initialising the two
-- together makes that divergence unrepresentable (DD-012, INV-12).
--
-- KEYS[1] masks      KEYS[2] holds      KEYS[3] freecount    KEYS[4] holddetail
-- ARGV[1] berthCount ARGV[2] segmentCount
-- ARGV[3..] optional: pre-occupied masks as "ordinal:lo:hi", for rebuild
--
-- Returns the number of berths provisioned.

local masks_key  = KEYS[1]
local holds_key  = KEYS[2]
local free_key   = KEYS[3]
local detail_key = KEYS[4]

local berth_count = tonumber(ARGV[1])
local segments    = tonumber(ARGV[2])

local mask_lo, mask_hi = {}, {}
for ordinal = 0, berth_count - 1 do
  mask_lo[ordinal] = 0
  mask_hi[ordinal] = 0
end

-- Rebuild path: confirmed allocations from Postgres are replayed in. Holds are
-- deliberately NOT restored - in-flight holds are lost on Redis loss, which §9.2
-- accepts and chaos C2 tests. Hold expiry itself stays durable in
-- bookings.hold_expires_at, so FR-24's decision survives the flush.
for i = 3, #ARGV do
  local ord, lo, hi = string.match(ARGV[i], '^(%d+):(%d+):(%d+)$')
  if ord then
    ord = tonumber(ord)
    mask_lo[ord] = tonumber(lo)
    mask_hi[ord] = tonumber(hi)
  end
end

local mask_parts = {}
for ordinal = 0, berth_count - 1 do
  mask_parts[ordinal + 1] = struct.pack('<I4I4', mask_lo[ordinal], mask_hi[ordinal])
end

-- Free counts derived FROM the masks rather than assumed to be berth_count.
-- On the rebuild path some berths are already occupied, and seeding the counts
-- to the full total there would recreate exactly the drift this script exists to
-- prevent.
local free_parts = {}
for seg = 0, segments - 1 do
  local free = 0
  for ordinal = 0, berth_count - 1 do
    local occupied
    if seg < 32 then
      occupied = bit.band(mask_lo[ordinal], bit.lshift(1, seg)) ~= 0
    else
      occupied = bit.band(mask_hi[ordinal], bit.lshift(1, seg - 32)) ~= 0
    end
    if not occupied then free = free + 1 end
  end
  free_parts[seg + 1] = struct.pack('<I4', free)
end

redis.call('SET', masks_key, table.concat(mask_parts))
redis.call('SET', free_key, table.concat(free_parts))
redis.call('DEL', holds_key)
redis.call('DEL', detail_key)

return berth_count
