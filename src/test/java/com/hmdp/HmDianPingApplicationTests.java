package com.hmdp;

import com.hmdp.entity.Follow;
import com.hmdp.entity.Shop;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {

    public static final ExecutorService es = Executors.newFixedThreadPool(500);
    @Resource
    public CacheClient cacheClient;
    @Resource
    public IShopService shopService;
    @Resource
    public StringRedisTemplate stringRedisTemplate;
    @Resource
    public RedissonClient redissonClient;
    @Resource
    public IFollowService followService;
    @Resource
    RabbitTemplate rabbitTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    void saveShop2RedisTest() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    /**
     * test unique ID
     */
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order:");
                log.info("id = {}", id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        log.info("time = {}", end - begin);
    }

    @Test
    void testRedisDistributedLock() throws InterruptedException {
        RLock lock = redissonClient.getLock("anyLock");
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        if (isLock) {
            try {
                log.info("抢到锁,执行业务");
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 把数据库里的商品地址信息缓存到redis中
     */
    @Test
    void saveGEOtoRedisTest() {
        List<Shop> shopList = shopService.list();
        Map<Long, List<Shop>> classified = shopList.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : classified.entrySet()) {
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            List<Shop> sameTypeShops = entry.getValue();//同类型的店铺list
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(sameTypeShops.size());
            for (Shop shop : sameTypeShops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }


    }

    /**
     * 把关注信息存到redis中
     */
    @Test
    void saveFollowersToRedisTest() {
        Map<Long, List<Follow>> classified = followService.list().stream().collect(Collectors.groupingBy(Follow::getUserId));
        for (Map.Entry<Long, List<Follow>> entry : classified.entrySet()) {
            Long userId = entry.getKey();
            List<Follow> followers = entry.getValue();
            if (followers == null || followers.isEmpty()) {
                continue;
            }
            List<Long> list = followers.stream().map(Follow::getFollowUserId).toList();
            String[] array = list.stream().map(String::valueOf).toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(FOLLOWS_KEY + userId, Arrays.toString(array));
        }
    }

    @Test
    public void testSendMessage() {
        rabbitTemplate.convertAndSend("hmdianping.direct", "direct.seckill", "测试发送消息");
    }

}
