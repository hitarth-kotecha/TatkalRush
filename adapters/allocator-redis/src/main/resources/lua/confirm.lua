-- Promotes a hold into a permanent allocation (FR-25).
--
-- The distinction from release.lua is the whole point: release frees the berths,
-- confirm KEEPS them and takes the hold out of the reaper's reach. Without that
-- second part a confirmed booking's berths would be swept the moment the original
-- TTL passed, releasing seats a passenger has paid for -- and INV-4 would find
-- the orphan long after the customer did.
--
-- Returning EXPIRED rather than erroring is deliberate. Payment settling after
-- the hold lapsed is FR-24's benign race, expected under chaos C2 and C5; the
-- caller auto-refunds with reason HOLD_EXPIRED. It must stay distinguishable from
-- an allocation conflict, which is a bug and fails the run (DD-008, INV-11).
--
-- KEYS[1] holds   KEYS[2] holddetail
-- ARGV[1] holdId
--
-- Returns {"OK", ordinal, ...} or {"EXPIRED"}

local holds_key  = KEYS[1]
local detail_key = KEYS[2]
local hold_id    = ARGV[1]

local detail = redis.call('HGET', detail_key, hold_id)
if not detail then
  return { 'EXPIRED' }
end

-- Presence in the ZSET is what makes a hold live. The reaper removes it there
-- first, so checking the detail hash alone could confirm a hold already being
-- swept.
if redis.call('ZSCORE', holds_key, hold_id) == false then
  redis.call('HDEL', detail_key, hold_id)
  return { 'EXPIRED' }
end

local _, _, ords = string.match(detail, '^(%d+):(%d+):(.*)$')

-- Out of the reaper's reach, masks untouched.
redis.call('ZREM', holds_key, hold_id)
redis.call('HDEL', detail_key, hold_id)

local reply = { 'OK' }
for ord in string.gmatch(ords, '(%d+)') do
  reply[#reply + 1] = tonumber(ord)
end
return reply
