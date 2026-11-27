-- KEYS: presence, stage, hand-raise queue, moderators
-- ARGV: hostId, successorUserId ('' if none), freshnessCutoffMillis, nowMillis, ttlSeconds, abruptExpiry ('1' or '0')
local hostId = ARGV[1]
local successorId = ARGV[2]
local cutoff = tonumber(ARGV[3])
local now = ARGV[4]
local ttl = ARGV[5]
local abrupt = ARGV[6] == '1'

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff - 1)

local active = {}
local hasOthers = false
for _, id in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
  if id ~= hostId then
    active[id] = true
    hasOthers = true
  end
end
for _, id in ipairs(redis.call('ZRANGE', KEYS[2], 0, -1)) do
  if id ~= hostId then
    active[id] = true
    hasOthers = true
  end
end

if not hasOthers then
  return {'CLOSE'}
end

local mods = {}
for _, id in ipairs(redis.call('SMEMBERS', KEYS[4])) do
  if active[id] then
    mods[id] = true
  end
end

local chosen = nil
if successorId ~= '' and mods[successorId] then
  chosen = successorId
else
  for _, id in ipairs(redis.call('ZRANGE', KEYS[2], 0, -1)) do
    if mods[id] then
      chosen = id
      break
    end
  end
  if not chosen then
    for _, id in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
      if mods[id] then
        chosen = id
        break
      end
    end
  end
end

if chosen then
  local wasOnStage = redis.call('ZSCORE', KEYS[2], chosen) ~= false
  redis.call('ZREM', KEYS[1], hostId)
  redis.call('ZREM', KEYS[2], hostId)
  redis.call('ZREM', KEYS[3], hostId)
  redis.call('SREM', KEYS[4], hostId)
  if not wasOnStage then
    redis.call('ZADD', KEYS[2], now, chosen)
    redis.call('EXPIRE', KEYS[2], ttl)
  end
  redis.call('ZREM', KEYS[3], chosen)
  redis.call('SREM', KEYS[4], chosen)
  return {'TRANSFER', chosen, wasOnStage and '1' or '0'}
end

if abrupt then
  return {'CLOSE_HOST_ABSENT'}
end

if successorId == '' then
  return {'NEED_SUCCESSOR'}
end

if successorId == hostId or not active[successorId] then
  return {'SUCCESSOR_NOT_IN_ROOM'}
end

local wasOnStage = redis.call('ZSCORE', KEYS[2], successorId) ~= false
redis.call('ZREM', KEYS[1], hostId)
redis.call('ZREM', KEYS[2], hostId)
redis.call('ZREM', KEYS[3], hostId)
redis.call('SREM', KEYS[4], hostId)
if not wasOnStage then
  redis.call('ZADD', KEYS[2], now, successorId)
  redis.call('EXPIRE', KEYS[2], ttl)
end
redis.call('ZREM', KEYS[3], successorId)
redis.call('SREM', KEYS[4], successorId)
return {'TRANSFER', successorId, wasOnStage and '1' or '0'}
