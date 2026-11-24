-- KEYS: presence, stage, hand-raise queue, comments, topic, muted users, banned users, grace lease.
-- ARGV: presence cutoff, page size, stage cap, user ID, is host, comment cap, now, state TTL, room seats.
local userId = ARGV[4]
local host = ARGV[5] == 'true'
if redis.call('SISMEMBER', KEYS[7], userId) == 1 then
  return {-1}
end

redis.call('ZADD', KEYS[1], 'GT', ARGV[7], userId)
redis.call('EXPIRE', KEYS[1], ARGV[8])
local entry = 0
if redis.call('ZSCORE', KEYS[2], userId) then
  entry = 1 + redis.call('DEL', KEYS[8])
elseif host and redis.call('ZCARD', KEYS[2]) < tonumber(ARGV[9]) then
  redis.call('ZADD', KEYS[2], ARGV[7], userId)
  redis.call('ZREM', KEYS[3], userId)
  redis.call('EXPIRE', KEYS[2], ARGV[8])
  entry = 1
end

local speakers = redis.call('ZRANGE', KEYS[2], 0, tonumber(ARGV[3]) - 1)
local seated = {}
local presentSpeakers = 0
for _, speaker in ipairs(speakers) do
  seated[speaker] = true
  local score = redis.call('ZSCORE', KEYS[1], speaker)
  if score and tonumber(score) >= tonumber(ARGV[1]) then
    presentSpeakers = presentSpeakers + 1
  end
end

local audience = {}
local pageSize = tonumber(ARGV[2])
local candidates = redis.call('ZREVRANGEBYSCORE', KEYS[1], '+inf', ARGV[1], 'LIMIT', 0, pageSize + tonumber(ARGV[3]) + 1)
for _, candidate in ipairs(candidates) do
  if not seated[candidate] then
    audience[#audience + 1] = candidate
    if #audience > pageSize then break end
  end
end

local hands = {}
if host then
  hands = redis.call('ZRANGE', KEYS[3], 0, pageSize - 1)
elseif redis.call('ZSCORE', KEYS[3], userId) then
  hands[1] = userId
end

local participants = redis.call('ZCOUNT', KEYS[1], ARGV[1], '+inf')
return {entry, speakers, audience, participants, participants - presentSpeakers, redis.call('ZCARD', KEYS[3]),
  hands, redis.call('LRANGE', KEYS[4], 0, tonumber(ARGV[6]) - 1), redis.call('GET', KEYS[5]),
  redis.call('SISMEMBER', KEYS[6], userId)}
