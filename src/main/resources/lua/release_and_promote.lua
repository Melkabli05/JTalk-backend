-- KEYS[1] = stage zset, KEYS[2] = hand-raise queue zset
-- ARGV[1] = leaving userId, ARGV[2] = max seats, ARGV[3] = now (epoch millis), ARGV[4] = key ttl seconds
-- Frees the leaving user's seat and, in the same atomic step, seats the longest-waiting hand-raiser if a seat
-- is free. Returns the promoted userId, or nil.
redis.call('ZREM', KEYS[1], ARGV[1])
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
  return false
end
local head = redis.call('ZRANGE', KEYS[2], 0, 0)
if #head == 0 then
  return false
end
redis.call('ZREM', KEYS[2], head[1])
redis.call('ZADD', KEYS[1], ARGV[3], head[1])
redis.call('EXPIRE', KEYS[1], ARGV[4])
return head[1]
