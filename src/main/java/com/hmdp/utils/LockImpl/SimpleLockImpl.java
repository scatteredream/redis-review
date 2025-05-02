package com.hmdp.utils.LockImpl;

import cn.hutool.core.lang.UUID;
import com.hmdp.utils.ILock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleLockImpl implements ILock {
    //锁: 具体的一个业务对应一把具体的锁也就是key名 此处name即为order:userId,用于解决同一个用户的高并发请求
    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //UUID + 线程id 在多个JVM实例上也不会出现重复
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final String threadId = ID_PREFIX + Thread.currentThread().threadId();

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    public SimpleLockImpl(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeout) {
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY_PREFIX + name, threadId, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }


    @Override
    public void unlock() {
//        String currentLockerId = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX + name);
//        if (StrUtil.equals(currentLockerId, threadId)) {
//            stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
//        }
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY_PREFIX + name), threadId);
    }
}
