-- 0. 键列表
local stockPrefix = KEYS[1]

local orderPrefix = KEYS[2]
-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id (为了使用消息队列将订单id传递到消息队列中)
--local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = stockPrefix .. "{" .. voucherId .."}"
-- 2.2.订单key
local orderKey = orderPrefix .. "{" .. voucherId .."}"

-- 3.脚本业务
--TODO 防止超卖 3.1.判断库存是否充足 get stockKey
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
--TODO 一人一单 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId 没有orderKey则自动创建
redis.call('sadd', orderKey, userId)
-- 3.6.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...
-- redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0