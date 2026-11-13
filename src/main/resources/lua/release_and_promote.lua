-- KEYS[1] = stage zset, KEYS[2] = hand-raise queue zset
-- ARGV[1] = leaving userId, ARGV[2] = now (epoch millis), ARGV[3] = key ttl seconds
-- If the user holds a seat, frees it and, in the same atomic step, seats the longest-waiting hand-raiser.
-- Returns the promoted userId, or nil (the user held no seat, or nobody was waiting).
if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then
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
