-- 0. 键列表
local stockPrefix = KEYS[1]
local orderPrefix = KEYS[2]

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


-- 3.4.恢复库存 incrby stockKey -1
redis.call('incrby', stockKey, 1)
-- 3.5.移除 srem orderKey userId
redis.call('srem', orderKey, userId)
-- 3.6.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...
-- redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0