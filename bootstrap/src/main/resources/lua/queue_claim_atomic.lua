-- Claim a queued request when it is inside the live head window, and clean expired zombie entries.
-- KEYS[1]: queue ZSET key
-- ARGV[1]: request ID
-- ARGV[2]: max live rank allowed
-- ARGV[3]: entry marker key prefix
local queueKey = KEYS[1]
local requestId = ARGV[1]
local maxRank = tonumber(ARGV[2])
local entryPrefix = ARGV[3]

local slack = 16
local headEntries = redis.call('ZRANGE', queueKey, 0, maxRank + slack - 1)

local liveRank = -1
local liveCount = 0
for i = 1, #headEntries do
    local member = headEntries[i]
    if redis.call('EXISTS', entryPrefix .. member) == 1 then
        if member == requestId then
            liveRank = liveCount
        end
        liveCount = liveCount + 1
    else
        redis.call('ZREM', queueKey, member)
    end
end

if liveRank < 0 or liveRank >= maxRank then return {0} end

local score = redis.call('ZSCORE', queueKey, requestId)

redis.call('ZREM', queueKey, requestId)
redis.call('DEL', entryPrefix .. requestId)

return {1, score}
