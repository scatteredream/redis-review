package com.hmdp.utils;


import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class RedisIdWorker {

    public static final long BEGIN_TIMESTAMP = 1730574577L;
    public static final int COUNT_BITS = 32;
    private final StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 时间戳+序列号 ID一共64位
     *
     * @param keyPrefix key前缀
     * @return 返回的id值 long
     */

    public long nextId(String keyPrefix) {
        //时间戳,支持69年
        LocalDateTime now = LocalDateTime.now();
        long epochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = epochSecond - BEGIN_TIMESTAMP;
        //序列号, 1秒能支持2^32个序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue()
                .increment("incr:" + keyPrefix + date);
//                .increment("incr:" + keyPrefix + ":" + date);
        if (count == null) {
            count = 0L;
        }
        //返回唯一ID
        return timeStamp << COUNT_BITS | count;
    }


}
