package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.hash.BloomFilter;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private BloomFilter<Long> bloomFilter;

    @Value("${custom.cache}")
    private String methodName;

    @PostConstruct
    public void preHandlingMethod() {
        if ("logicalexpire".equals(methodName) || "mutex".equals(methodName)) {
            warming();
            return;
        }
        if (methodName.equals("nullobject")) {
            return;// do nothing
        } else {
            List<Long> list = query().list().stream().map(Shop::getId).toList();
            for (Long id : list) {
                bloomFilter.put(id);
            }
        }
    }

    public void warming() {
        List<Shop> allShops = query().list();
        for (Shop shop : allShops) {
            // 将商铺信息放入Redis
            String key = CACHE_SHOP_KEY + shop.getId();
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        }
    }

    @Override
    public Result queryById(Long id) {
        Function<Long, Shop> dbFallback = this::getById;
        Shop shopData;
        switch (methodName) {
            case "nullobject" ->
                    shopData = cacheClient.getAvoidPenetrationUsingNullObject(CACHE_SHOP_KEY, id, Shop.class, dbFallback);
            case "logicalexpire" ->
                    shopData = cacheClient.getAvoidHotSpotInvalidUsingLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, dbFallback);
            case "mutex" ->
                    shopData = cacheClient.getAvoidHotspotInvalidUsingMutex(CACHE_SHOP_KEY, id, Shop.class, dbFallback);
            default -> shopData = cacheClient.getAvoidPenetrationUsingBloom(CACHE_SHOP_KEY, id, Shop.class, dbFallback);
        }
        if (shopData == null) {
            return Result.fail("查询商铺不存在:" + id);
        }
        return Result.ok(shopData);
    }


    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        // TODO 1. 更新数据库
        if (!updateById(shop)) {
//            httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return Result.fail("商铺更新失败: " + id);
        }
        // TODO 2. 删除redis缓存
        String shopKey = CACHE_SHOP_KEY + id;
        Boolean isDelete = stringRedisTemplate.delete(shopKey);
        return Result.ok(isDelete);
    }


    @Override
    public Result getShopByTypeId(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int from = (current - 1) * DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            return Result.ok();
        }
        //result里面有GeoLocation(shopId+Point坐标)+Distance
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> resultMap = new HashMap<>(content.size());

        content.stream().skip(from).forEach(result -> {
            Distance distance = result.getDistance();//拿到距离
            String shopId = result.getContent().getName();//拿到位置的name也就是id
            ids.add(Long.valueOf(shopId));
            resultMap.put(shopId, distance);
        });
        //按照id查询,并且要返回按照指定排序好的list
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        //distance不是数据库字段,需要手动设置值
        for (Shop shop : shops) {
            shop.setDistance(resultMap.get(shop.getId().toString()).getValue());
        }
        // 返回数据
        return Result.ok(shops);
    }
    @Transactional
    @Override
    public Result saveShop(Shop shop) {
        save(shop); // 写入数据库
        // 写入Redis
        String key = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        // 写入 BloomFilter
        if ("bloom".equals(methodName)) {
            bloomFilter.put(shop.getId());
        }
        return Result.ok(shop.getId());
    }
}
