package com.hmdp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryShopType() {
        //TODO 1. 查询商铺，如果redis中根本没有这个键,直接进行查数据库的操作
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(CACHE_SHOP_TYPE_KEY))) {
            return setShopTypeRedis();
        }
        //TODO 2. 如果redis中有这个键,但是size=0,查数据库
        Long size = stringRedisTemplate.opsForList().size(CACHE_SHOP_TYPE_KEY);
        if (size == null || size == 0) { //说明此时是个空键，继续查数据库
            return setShopTypeRedis();
        } else {//TODO 3. redis中有相关数据,解析成list返回
            List<String> listOfJson = stringRedisTemplate.opsForList()
                    .range(CACHE_SHOP_TYPE_KEY, 0, size - 1);
            if (listOfJson == null || listOfJson.isEmpty()) {
                return null;
            }
            JSONArray jsonArray = JSONUtil.parseArray(listOfJson.toString());
            return JSONUtil.toList(jsonArray, ShopType.class);
        }
    }

    //TODO 0.数据库查出来的信息更新到缓存里
    public List<ShopType> setShopTypeRedis() {
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //将List of ShopType 转换成 List of Json
        for (ShopType shopType : shopTypeList) {
            stringRedisTemplate.opsForList().rightPush(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopType));
        }
        return shopTypeList;
    }
}
