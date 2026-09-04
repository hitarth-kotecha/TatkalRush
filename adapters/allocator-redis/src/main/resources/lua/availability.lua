-- Free berths across a segment range (FR-13).
--
-- The MINIMUM across the range, never an average: a journey needs the same berth
-- for its whole length, so a route with 40 berths free on three segments and one
-- free on the fourth has one berth available, not thirty-one.
--
-- Reaping is deliberately NOT done here. This is a read on the search path, which
-- runs at roughly nine times the rate of booking (§19's P2 is 90% search), and
-- making it a write would put that entire load onto the structure the allocator
-- needs. The cost is that this can report a berth as taken for up to one hold TTL
-- after it truly expired -- conservative in the safe direction, and FR-13 already
-- calls availability approximate.
--
-- KEYS[1] freecount
-- ARGV[1] fromSeq   ARGV[2] toSeq   ARGV[3] segmentCount
--
-- Returns the free berth count, or an error if the pool is not provisioned.

local free_key = KEYS[1]
local from_seq = tonumber(ARGV[1])
local to_seq   = tonumber(ARGV[2])
local segments = tonumber(ARGV[3])

local free = redis.call('GET', free_key)
if not free then
  return redis.error_reply('free counts not initialised: ' .. free_key)
end
if #free ~= segments * 4 then
  return redis.error_reply(
    'segment count mismatch for ' .. free_key .. ': pool has ' .. (#free / 4)
    .. ' segments, request says ' .. segments)
end

local minimum = nil
for seg = from_seq, to_seq - 1 do
  local count = struct.unpack('<I4', free, seg * 4 + 1)
  if minimum == nil or count < minimum then
    minimum = count
  end
end

return minimum or 0
