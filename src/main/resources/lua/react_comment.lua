-- KEYS[1] = presence zset, KEYS[2] = chat-muted set, KEYS[3] = comments list (newest first), KEYS[4] = reaction counts hash (commentId|REACTION -> people),
-- KEYS[5] = this person's reactions hash (commentId -> REACTION)
-- ARGV[1] = userId, ARGV[2] = commentId, ARGV[3] = the reaction, or empty to take it back, ARGV[4] = key ttl seconds,
-- ARGV[5] = the oldest join (epoch millis) that still counts as being in the room
-- Refuses someone who is not in the room (the same rule as PresenceRepository.isPresent), then a muted user, then a comment that is not in the
-- chat any more; otherwise sets, changes or removes the person's one reaction to the comment and moves the counts with it.
-- Returns: {-3} = not in the room, {-1} = muted, {-4} = no such comment, {0} = nothing changed, {1, previous} = changed (previous is empty the first time)
local seen = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not seen or tonumber(seen) < tonumber(ARGV[5]) then
  return {-3}
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return {-1}
end
local found = false
for _, raw in ipairs(redis.call('LRANGE', KEYS[3], 0, -1)) do
  if cjson.decode(raw).id == ARGV[2] then
    found = true
    break
  end
end
if not found then
  return {-4}
end
local previous = redis.call('HGET', KEYS[5], ARGV[2]) or ''
if previous == ARGV[3] then
  return {0}
end
if previous ~= '' then
  if redis.call('HINCRBY', KEYS[4], ARGV[2] .. '|' .. previous, -1) <= 0 then
    redis.call('HDEL', KEYS[4], ARGV[2] .. '|' .. previous)
  end
end
if ARGV[3] == '' then
  redis.call('HDEL', KEYS[5], ARGV[2])
else
  redis.call('HSET', KEYS[5], ARGV[2], ARGV[3])
  redis.call('HINCRBY', KEYS[4], ARGV[2] .. '|' .. ARGV[3], 1)
end
redis.call('EXPIRE', KEYS[4], tonumber(ARGV[4]))
redis.call('EXPIRE', KEYS[5], tonumber(ARGV[4]))
return {1, previous}
