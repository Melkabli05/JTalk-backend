-- KEYS[1] = stage zset, KEYS[2] = hand-raise queue zset
-- ARGV[1] = max seats, ARGV[2] = now (epoch millis), ARGV[3] = key ttl seconds
-- Seats the longest-waiting hand-raiser if a seat is free. Returns their userId, or nil.
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then
  return false
end
local head = redis.call('ZRANGE', KEYS[2], 0, 0)
if #head == 0 then
  return false
end
redis.call('ZREM', KEYS[2], head[1])
redis.call('ZADD', KEYS[1], ARGV[2], head[1])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return head[1]
