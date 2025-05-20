local current_tokens = tonumber(redis.call('hget', KEYS[1], 'current_tokens'))  
if current_tokens > 0 then
    redis.call('hincrby', KEYS[1], 'current_tokens', -1)
    return 1
else
    return 0
end