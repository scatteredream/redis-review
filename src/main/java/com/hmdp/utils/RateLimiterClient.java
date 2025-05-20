package com.hmdp.utils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RateLimiterClient {
    @Resource
    private StringRedisTemplate redisTemplate;

    private static final DefaultRedisScript<Long> RATE_LIMITER_SCRIPT;

    static{
        RATE_LIMITER_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMITER_SCRIPT.setLocation(new ClassPathResource("rate_limiter.lua"));
        RATE_LIMITER_SCRIPT.setResultType(Long.class);
    }
    @PostConstruct
    public void init() {
        // 初始化令牌桶
        redisTemplate.opsForHash().put("token_bucket", "current_tokens", 0);
        redisTemplate.opsForHash().put("token_bucket", "max_tokens", 100);
        redisTemplate.opsForHash().put("token_bucket", "tokens_per_second", 10);
        redisTemplate.opsForHash().put("token_bucket", "last_replenish", System.currentTimeMillis());
    }

    public boolean tryAcquireToken() {
        // 尝试获取一个令牌
        // 使用Lua脚本确保原子性
        Long result = redisTemplate.execute(RATE_LIMITER_SCRIPT, List.of("token_bucket"));
        // 没有令牌
        return result == 1; // 获得令牌
    }
    // 定时任务，每秒执行一次
    @Scheduled(fixedRate = 1000)
    public void addTokens() {
        long current = System.currentTimeMillis();
        Object last = redisTemplate.opsForHash().get("token_bucket", "last_replenish");
        if(last == null) return;
        long elapsed = (current - (long) last) / 1000;
        if (elapsed > 0) {
            Object tokensPerSecond = redisTemplate.opsForHash().get("token_bucket", "tokens_per_second");
            Object maxTokens =  redisTemplate.opsForHash().get("token_bucket", "max_tokens");
            Object currentTokens = redisTemplate.opsForHash().get("token_bucket", "current_tokens");
            if(tokensPerSecond == null || maxTokens == null || currentTokens == null) return;

            int newTokens = Math.toIntExact(Math.min ((int)tokensPerSecond * (int)elapsed, (int)maxTokens - (int)currentTokens));
            redisTemplate.opsForHash().put("token_bucket", "current_tokens", current + newTokens);
            redisTemplate.opsForHash().put("token_bucket", "last_replenish", current);
        }
    }


}
