-- Reads a pool's full mask and free-count state (§9.2).
--
-- Needed by three callers, none of which can settle for "the results matched":
--
--   T-7  compares Strategy A's state against the Java reference after EVERY
--        step. §9.2 is explicit about why: a Lua bug that returns the right
--        answer via wrong state -- decrementing a free count by 1 instead of
--        passengerCount, say -- passes the contract suite and silently corrupts
--        availability for the rest of the run.
--   INV-8 / INV-12  check masks and free counts agree, post-run and quiesced.
--   §13.4  verifies the rebuild after chaos C2 restored BOTH.
--
-- Unpacks in Lua rather than shipping the blobs to Java, because the blobs are
-- binary and the client speaks a String codec: a GET here would be UTF-8 decoded
-- and mangled. Returning numbers sidesteps the codec entirely.
--
-- KEYS[1] masks   KEYS[2] freecount
--
-- Returns { {lo, hi, lo, hi, ...}, {free, free, ...} }

local masks = redis.call('GET', KEYS[1])
local free = redis.call('GET', KEYS[2])

if not masks or not free then
  return redis.error_reply('pool not initialised: ' .. KEYS[1])
end

local berth_count = #masks / 8
local segments = #free / 4

local mask_out = {}
for ordinal = 0, berth_count - 1 do
  local lo, hi = struct.unpack('<I4I4', masks, ordinal * 8 + 1)
  mask_out[#mask_out + 1] = lo
  mask_out[#mask_out + 1] = hi
end

local free_out = {}
for seg = 0, segments - 1 do
  free_out[#free_out + 1] = struct.unpack('<I4', free, seg * 4 + 1)
end

return { mask_out, free_out }
