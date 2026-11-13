-- KEYS[1] = stage zset, KEYS[2] = hand-raise queue zset
-- ARGV[1] = userId, ARGV[2] = max seats, ARGV[3] = now (epoch millis), ARGV[4] = key ttl seconds
-- Seats the user atomically, and takes them off the hand-raise queue.
-- Returns: 0 = stage full, 1 = was already on stage, 2 = seated
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
  redis.call('ZREM', KEYS[2], ARGV[1])
  return 1
end
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
  return 0
end
redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 2
