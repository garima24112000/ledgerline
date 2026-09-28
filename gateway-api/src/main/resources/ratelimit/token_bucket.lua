-- Token bucket: refill and take in one atomic step.
-- Redis runs a script to completion before any other command, so two gateway instances can
-- never both read "1 token left" and both take it.
--
-- KEYS[1] = bucket key (one HASH per merchant: tokens, ts_ms)
-- ARGV[1] = refill rate, tokens per second
-- ARGV[2] = burst (bucket capacity)
-- Returns {allowed (1/0), tokens left (rounded down), retry after in ms (0 when allowed)}

local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])

-- Redis' clock, not the app's: every gateway instance sees the same time.
-- Milliseconds keep the number at 13 digits, which tostring() stores exactly.
local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts_ms')
local tokens = tonumber(state[1])
local last_ms = tonumber(state[2])
if tokens == nil or last_ms == nil then
    tokens = burst -- a new (or expired) bucket starts full
    last_ms = now_ms
end

-- max(0, ...) guards against the clock stepping backwards.
local elapsed_ms = math.max(0, now_ms - last_ms)
tokens = math.min(burst, tokens + elapsed_ms * rate / 1000)

local allowed = 0
local retry_after_ms = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retry_after_ms = math.ceil((1 - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts_ms', tostring(now_ms))
-- After this long without requests the bucket would be full again anyway, so it can go.
redis.call('PEXPIRE', KEYS[1], math.ceil(burst * 1000 / rate) + 1000)

return {allowed, math.floor(tokens), retry_after_ms}
