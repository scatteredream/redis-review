package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    //TTL: Time to Live
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 360000000000L;

    public static final Long CACHE_NULL_TTL = 2L;//原版

    public static final Long CACHE_DATA_TTL = 30L;//一般数据的TTL
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shoptype";

    public static final String LOCK_SHOP_KEY = "lock:shop:";//商铺互斥锁的key
    public static final Long LOCK_TTL = 10L;//一般互斥锁的TTL

    public static final String ORDER_KEY_PREFIX = "order:";

    public static final String SECKILL_STOCK_PREFIX = "seckill:stock";

    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String FOLLOWS_KEY = "follows:";

}
