-- KEYS[1] = banned set, KEYS[2] = stage zset, KEYS[3] = presence zset, KEYS[4] = this user's grace lease
-- ARGV[1] = userId, ARGV[2] = now (epoch millis), ARGV[3] = key ttl seconds
-- Refuses a user the host removed. Otherwise marks them present (never lowering a score the media server already confirmed) and, if they
-- hold a seat, releases the grace lease they were on: they are back within it.
-- Returns: -1 = removed by the host, 0 = in, not on stage, 1 = in, on stage, 2 = in, on stage, and a grace lease was released
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
  return -1
end
redis.call('ZADD', KEYS[3], 'GT', ARGV[2], ARGV[1])
redis.call('EXPIRE', KEYS[3], ARGV[3])
if redis.call('ZSCORE', KEYS[2], ARGV[1]) then
  if redis.call('DEL', KEYS[4]) > 0 then
    return 2
  end
  return 1
end
return 0
