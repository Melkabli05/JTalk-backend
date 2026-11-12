-- KEYS[1] = stage zset, KEYS[2] = hand-raise queue zset
-- ARGV[1] = userId, ARGV[2] = max seats, ARGV[3] = now (epoch millis), ARGV[4] = key ttl seconds
-- Moves a user from the queue onto the stage atomically.
-- Returns: 1 = seated, 0 = stage full, 2 = already on stage, 3 = not in queue
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
  return 2
end
if not redis.call('ZSCORE', KEYS[2], ARGV[1]) then
  return 3
end
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
  return 0
end
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 1
