-- KEYS[1] = chat-muted set, KEYS[2] = this user's comment counter, KEYS[3] = comments list (newest first), KEYS[4] = presence zset
-- ARGV[1] = userId, ARGV[2] = the comment as JSON, ARGV[3] = comments allowed per window, ARGV[4] = window seconds,
-- ARGV[5] = comments kept, ARGV[6] = key ttl seconds, ARGV[7] = the oldest join (epoch millis) that still counts as being in the room
-- Refuses someone who is not in the room (the same rule as PresenceRepository.isPresent), then a muted user, then one who comments too fast
-- (a fixed window: the counter starts with the first comment and expires with the window), then keeps the comment and drops the oldest beyond
-- the ones kept. Someone who is not in the room costs nothing but this check: no counter is created for them.
-- Returns: -3 = not in the room, -1 = muted, -2 = too fast, 1 = kept
local seen = redis.call('ZSCORE', KEYS[4], ARGV[1])
if not seen or tonumber(seen) < tonumber(ARGV[7]) then
  return -3
end
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
  return -1
end
local count = redis.call('INCR', KEYS[2])
if count == 1 then
  redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4]))
end
if count > tonumber(ARGV[3]) then
  return -2
end
redis.call('LPUSH', KEYS[3], ARGV[2])
redis.call('LTRIM', KEYS[3], 0, tonumber(ARGV[5]) - 1)
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[6]))
return 1
