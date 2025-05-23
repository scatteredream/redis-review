package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.OrderStatus;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.hmdp.utils.RedisConstants.*;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    //判断下单资格脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("secKill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService secKillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        // 设置Confirm回调
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            assert correlationData != null;
            if (ack) {
                log.info("Exchange received the message successfully: {}", correlationData.getId());
            } else {
                log.warn("Failed to arrive exchange: {} message: {}", cause, correlationData.getId());
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            log.warn("Failed to route: " + returned.getMessage());
        });
        rabbitTemplate.setMandatory(true);
    }

    @Autowired
    private IVoucherOrderService proxy;

    private final ExecutorService executorService = Executors.newFixedThreadPool(1);

    public Map<Long, OrderStatus> orderStatus = new ConcurrentHashMap<>();

    /**
     * 判断是否有下单资格,解决了超卖和一人一单的问题
     * <p>创建订单并加入阻塞队列等待线程执行</p>
     *
     * @param voucherId 秒杀券ID
     */
    @Override
    public Result secKillOrderLuaScript(Long voucherId) {
        Long orderId = redisIdWorker.nextId(ORDER_KEY_PREFIX);
        Long userId = UserHolder.getUser().getId();
        //result:执行lua脚本 redis是单线程 所以不用担心线程安全问题
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Arrays.asList(SECKILL_STOCK_PREFIX,SECKILL_ORDER_PREFIX),
                voucherId.toString(),
                userId.toString());
        assert result != null;
        int r = result.intValue();// TODO 判断是否有下单的资格
        if (r != 0) {
            return Result.fail(r == 1 ? "限量优惠券已抢完" : "您已经抢过此限量优惠券了");
        }
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        rabbitTemplate.convertAndSend("hmdianping.direct",
                "direct.seckill", order, message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setHeader("retry-count", "0");
                        return message;
                    },
                new CorrelationData("orderId-"+orderId)
        );
        // 将订单状态存到 OrderStatus 内存中
        orderStatus.putIfAbsent(orderId, OrderStatus.PENDING);
        return Result.ok(orderId);
    }

    /**
     * <p>异步创建订单,与之前的不同点是订单所有信息是提前生成的</p>
     * 之前已经判断了超卖和一人一单,此处只是持久化到数据库的操作
     *
     * @param order 提前创建好的订单对象
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean createOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();
        Long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过了");
            return false;
        }
        //乐观锁核心: stock和之前相等才更新 问题: 如果两个线程发生了冲突,必定有一个会失败
        //库存比较特殊可以并发扣减, stock > 0 就可以更新
        //如果要求真正的乐观锁,可以把数据分到多个数据库里(concurrentHashMap）成倍地提高成功率
        //如果没有数据库的锁,多个线程可以同时对数据进行修改,直接影响业务层
        boolean success = secKillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();  //只是限量,不是唯一,所以只需要保证stock大于0即可
//                .eq("stock", formerStock).update();//如果两个线程发生了冲突,必定有一个会失败
        if (!success) {
            log.error("库存不足");
            return false;
        }
        return save(order);
    }

    @Override
    public OrderStatus getOrderStatus(Long orderId) {
        if(orderId == null) {
            return null;
        }
        OrderStatus status = orderStatus.get(orderId);
        if(status == OrderStatus.PENDING) {
            return OrderStatus.PENDING;
        }
        // 如果是 成功 或者 失败 就删除并返回(防止内存溢出)
        // 可以继承map做一个ttlMap, 定时任务清除已经过期的条目.
        return orderStatus.remove(orderId);
    }

    @Override
    public void setOrderStatus(Long orderId, OrderStatus status) {
        orderStatus.putIfAbsent(orderId, status);
        return;
    }

    /**
     * 分布式锁实现一人一单
     *
     * @param voucherId 秒杀券ID
     */
    @SneakyThrows
    @Override
    public Result secKillOrderRedisson(Long voucherId) {
        // 查数据库
        SeckillVoucher secKillVoucher = secKillVoucherService.getById(voucherId);
        LocalDateTime beginTime = secKillVoucher.getBeginTime();
        LocalDateTime endTime = secKillVoucher.getEndTime();
        Integer formerStock = secKillVoucher.getStock();

        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("活动未开始");
        }
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("活动已结束");
        }
        if (formerStock < 1) {
            return Result.fail("限量优惠券已经抢完");
        }
        //在事务外面加锁
        Long userId = UserHolder.getUser().getId();
//        ILock myLock = new SimpleLockImpl(ORDER_KEY_PREFIX + userId, stringRedisTemplate);
        Future<Result> submit = executorService.submit(() -> {
            RLock lock = redissonClient.getLock(ORDER_KEY_PREFIX + userId);

            if (!lock.tryLock()) {
                return Result.fail("您抢得太快啦,请稍事休息再来");
            }
            try {
                return proxy.createOrderById(voucherId);
            } finally {
                lock.unlock();
            }
    /*
        不适合集群和分布式应用
        synchronized (userId.toString().intern()) {
            //开启事务要用代理对象
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.handleVoucherOrder(voucherId);
        }
        */
        });
        return submit.get();
    }

    /**
     * 同步创建订单 乐观锁防止超卖
     * 订单ID是现场创建的
     *
     * @param voucherId 秒杀券ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)//自己是一个事务
    public Result createOrderById(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("已经抢过此限量优惠券了");
        }
        boolean success = secKillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();  //只是限量,不是唯一,所以只需要保证stock大于0即可
//                .eq("stock", formerStock).update();//如果两个线程发生了冲突,必定有一个会失败

        if (!success) {
            return Result.fail("获取优惠券失败,请稍后再试");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId(ORDER_KEY_PREFIX);

        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        return Result.ok(orderId);
    }

    /**
     * 更新数据库的任务实现类
     */
    @Deprecated
    private class VoucherOrderHandler implements Runnable {
        @Deprecated
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list =
                            stringRedisTemplate.opsForStream().read(
                                    Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                    StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                            );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.getFirst();
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
//                    handleVoucherOrder(voucherOrder);
                    proxy.createOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }

        /**
         * 异常消息处理:从pending-list中读取
         */
        @Deprecated
        @SuppressWarnings("unused")
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list =
                            stringRedisTemplate.opsForStream().read(
                                    Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1),
                                    StreamOffset.create("stream.orders", ReadOffset.from("0"))
                            );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.getFirst();
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
//                    handleVoucherOrder(voucherOrder);
                    proxy.createOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (Exception exception) {
                        log.error("interrupted", e);
                    }
                }
            }
        }


        /**
         * 分布式锁 进行一个兜底 (optional)
         *
         * @param order 预先创建好的订单对象
         */
        @Deprecated
        private void handleVoucherOrder(VoucherOrder order) {
            //1.获取用户 todo 注意是从order中取用户id
            Long userId = order.getUserId();
            // 2.创建锁对象
            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
            // 3.尝试获取锁
            boolean isLockGot = redisLock.tryLock();
            // 4.判断是否获得锁成功
            if (!isLockGot) {
                // 获取锁失败，直接返回失败或者重试
                log.error("不允许重复下单!!!!");
                return;
            }
            try {
                //异步持久化到数据库
                //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
                proxy.createOrder(order);
            } finally {
                // 释放锁
                redisLock.unlock();
            }
        }
    }

}
