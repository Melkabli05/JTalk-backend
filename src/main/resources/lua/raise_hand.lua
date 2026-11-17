-- KEYS[1] = stage zset, KEYS[2] = hand-raise queue zset
-- ARGV[1] = userId, ARGV[2] = now (epoch millis, queue order), ARGV[3] = key ttl seconds
-- Returns: -1 if the user is already on stage, 0 if they were already queued, otherwise their new 1-based queue position.
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
  return -1
end
local added = redis.call('ZADD', KEYS[2], 'NX', ARGV[2], ARGV[1])
redis.call('EXPIRE', KEYS[2], ARGV[3])
if added == 0 then
  return 0
end
return redis.call('ZRANK', KEYS[2], ARGV[1]) + 1
