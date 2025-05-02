package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.google.common.hash.BloomFilter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 缓存工具类
 * </p>
 *
 * @author scatteredream
 * @since 2024-11-01
 */
@Slf4j
@Component
public class CacheClient {
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final StringRedisTemplate stringRedisTemplate;
    @Resource
    private BloomFilter<Long> bloomFilter;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void setWithTTL(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 防止大量针对空数据的查询导致的缓存穿透(Cache Penetration)
     *
     * @param keyPrefix  数据在redis的key前缀
     * @param id         ID
     * @param clazz      数据类型
     * @param dbFallback 查询数据库的操作
     * @param <R>        数据类型
     * @param <ID>       ID类型
     * @return 实体数据
     */
    public <R, ID> R getAvoidPenetrationUsingNullObject(String keyPrefix, ID id, Class<R> clazz, Function<ID, R> dbFallback) {
        String entityKey = keyPrefix + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(entityKey);
        //TODO 1. 缓存命中真实数据, 直接返回数据
        if (StrUtil.isNotBlank(jsonStr)) {
            return JSONUtil.toBean(jsonStr, clazz);
        }
        //TODO 2. 缓存命中空数据, 返回null数据
        if (jsonStr != null) {
            return null;
        }
        //TODO 3. 缓存未命中,查数据库
        R dataBaseResult = dbFallback.apply(id);
        //TODO 4. 数据库也没有,在redis建立空缓存 并返回null数据
        if (dataBaseResult == null) {
            setWithTTL(entityKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //TODO 5. 数据库存在,在redis建立缓存 并返回数据
        String jsonStrCache = JSONUtil.toJsonStr(dataBaseResult);
        setWithTTL(entityKey, jsonStrCache, CACHE_DATA_TTL, TimeUnit.MINUTES);
        return dataBaseResult;
    }

    /**
     * 使用逻辑过期(Logical Expire)防止热点数据过期,造成缓存击穿(Hotspot Invalid)
     * <div>缓存过期则另开线程进行缓存重建,重建完成之前查到的都是旧数据</div>
     *
     * @param <R>        数据的类型泛型
     * @param <ID>       ID的类型泛型
     * @param keyPrefix  数据在redis的key前缀
     * @param id         ID
     * @param clazz      数据的类型
     * @param dbFallback 建立缓存要进行的数据库操作
     * @return 实体数据
     */
    public <R, ID> R getAvoidHotSpotInvalidUsingLogicalExpire(String keyPrefix, ID id, Class<R> clazz, Function<ID, R> dbFallback) {
        String entityKey = keyPrefix + id;
        String lockKey = keyPrefix.replace("cache:", "lock:") + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(entityKey);
        //TODO 0. 缓存未命中(一般不可能,因为热点数据要提前预热好)
        if (StrUtil.isBlank(jsonStr)) {
            return null;
        }
        //TODO 1. 将redis数据转成RedisData, 从redisData中提取过期时间和实体对象
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
        //TODO 1.5 未过期就直接返回
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }
        //TODO 2. 已过期
        // 拿到锁,另开线程进行缓存重建
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R dataBaseResult = dbFallback.apply(id);
                    setWithLogicalExpire(entityKey, dataBaseResult, CACHE_DATA_TTL, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    releaseLock(lockKey);
                }
            });
        }
        //TODO 3.不论拿没拿到锁均返回过期值
        return r;
    }


    /**
     * <div>使用互斥锁(Mutex)防止热点数据过期造成<span style="font-weight: bold">缓存击穿</span>(Hotspot Invalid) <div/>
     * <div>缓存未命中则尝试获取互斥锁进行缓存重建. 获取失败则一直按照 递归调用,查缓存,抢锁,递归调用的顺序进行<div/>
     * 使用缓存空对象防止<span style="font-weight: bold">缓存穿透</span>
     *
     * @param <R>        数据的类型泛型
     * @param <ID>       ID的类型泛型
     * @param keyPrefix  数据在redis的key前缀
     * @param id         ID
     * @param clazz      数据的类型
     * @param dbFallback 建立缓存要进行的数据库操作
     * @return 实体数据
     */
    public <R, ID> R getAvoidHotspotInvalidUsingMutex(String keyPrefix, ID id, Class<R> clazz, Function<ID, R> dbFallback) {
        //TODO 0. Redis数据结构：cache:shop:key+id 对应 一条Json数据,
        String entityKey = keyPrefix + id;
        String lockKey = keyPrefix.replace("cache", "lock") + id;
        //TODO 1. 先到Redis中按照ID进行查询
        String json = stringRedisTemplate.opsForValue().get(entityKey);
        //TODO 2. redis查到直接返回对象
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, clazz);
        }
        //TODO 2.5. 查到如果不是null值而是空字符串，返回null对象
        if (json != null) {
            return null;
        }
        R dbResult;
        //TODO 3 尝试获取互斥锁
        try {
            if (!tryLock(lockKey)) {
                //没获取到,休眠然后递归进行方法的重试
                //TODO 3.5 为什么是递归执行原始方法?
                // 如果是循环获取锁,可能会导致这个线程一开始没命中,想要更新缓存
                // 获取互斥锁失败以后,一直想要更新缓存,但是别人占用锁就是在更新缓存啊!
                // 所以拘泥于反复争抢互斥锁,是没有意义的,重要的是缓存命中与否
                Thread.sleep(30);
                return getAvoidHotspotInvalidUsingMutex(keyPrefix, id, clazz, dbFallback);
            }
            //TODO 4.1 查数据库
            dbResult = dbFallback.apply(id);
            //模拟延迟
            Thread.sleep(200);
            if (dbResult == null) {
                //TODO 4.2 数据库查不到则向redis写入一个空值, 返回null对象
                stringRedisTemplate.opsForValue().set(entityKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return null;
            }
            //TODO 4.3 把查到的数据写到redis中，然后返回,读操作，更新缓存设置过期时间
            String jsonStrToCache = JSONUtil.toJsonStr(JSONUtil.parseObj(dbResult, false));
            stringRedisTemplate.opsForValue().set(entityKey, jsonStrToCache, CACHE_DATA_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //TODO 5 释放锁
            releaseLock(lockKey);
        }
        return dbResult;
    }

    /**
     * 尝试获取互斥锁
     *
     * @param key redis的锁key
     * @return 是否获取成功
     */
    public boolean tryLock(String key) {
        Boolean isLockGot = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLockGot);
    }

    /**
     * 释放互斥锁
     *
     * @param key redis的锁key
     */
    public void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public <R, ID> R getAvoidPenetrationUsingBloom(String keyPrefix, ID id, Class<R> clazz, Function<ID, R> dbFallback) {
        if (!bloomFilter.mightContain((Long) id)) {
            log.warn("shop [{}] might not contained in filter", id);
            return null;
        }
        String entityKey = keyPrefix + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(entityKey);
        //TODO 1. 缓存命中真实数据, 直接返回数据
        if (StrUtil.isNotBlank(jsonStr)) {
            return JSONUtil.toBean(jsonStr, clazz);
        }
//        if (jsonStr != null) {
//            return null;
//        }
        //TODO 3. 缓存未命中,查数据库
        R dataBaseResult = dbFallback.apply(id);
        //TODO 4. 数据库也没有,在redis建立空缓存 并返回null数据
        if (dataBaseResult == null) {
            setWithTTL(entityKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //TODO 5. 数据库存在,在redis建立缓存 并返回数据
        String jsonStrCache = JSONUtil.toJsonStr(dataBaseResult);
        setWithTTL(entityKey, jsonStrCache, CACHE_DATA_TTL, TimeUnit.MINUTES);
        return dataBaseResult;
    }


}
