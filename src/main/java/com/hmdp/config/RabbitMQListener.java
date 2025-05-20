package com.hmdp.config;


import com.hmdp.entity.FailedVoucherOrder;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IFailedVoucherOrderService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.OrderStatus;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PREFIX;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_PREFIX;


@Component
@Slf4j
public class RabbitMQListener {
    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderService;
    
    @Resource
    private IFailedVoucherOrderService failedVoucherOrderService;

    @Resource
    private ThreadPoolTaskExecutor orderTaskExecutor;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("rollback_secKill.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "direct.seckill.queue",
                    durable = "true",
                    arguments = {
                            @Argument(name = "x-dead-letter-exchange", value = "hmdianping.dlx"),
                            @Argument(name = "x-dead-letter-routing-key", value = "dlx.seckill")
                    }
            ),
            key = "direct.seckill",
            exchange = @Exchange(name = "hmdianping.direct")
    ))
    public void receiveMessage(Message message, Channel channel, VoucherOrder voucherOrder) {
        orderTaskExecutor.submit(() -> {
            log.info("收到订单: {}", voucherOrder);
            Object header = message.getMessageProperties().getHeader("retry-count");
            int retry = Integer.parseInt((String) header);
            try{
                if(retry > 3){
                    log.error("订单处理失败，超过最大重试次数: 3  {}", voucherOrder);
                    log.warn("发送到死信队列...{}",voucherOrder);
                    message.getMessageProperties().setHeader("retry-count", "0");
                    channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
                    log.warn("发送完成 {}",voucherOrder);
                    return;
                }
                boolean success = voucherOrderService.createOrder(voucherOrder);
                if(success){
                    channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                    // 成功,设置订单状态
                    voucherOrderService.setOrderStatus(voucherOrder.getId(), OrderStatus.SUCCESS);
                }
                else
                    throw new RuntimeException("数据库处理异常!");
            } catch (Exception e) {
                log.error("处理订单失败,准备重试: {}", voucherOrder, e);
                try {
                    message.getMessageProperties().setHeader("retry-count", String.valueOf(retry + 1));
                    log.info("第 {} 次重试: {}", retry + 1, voucherOrder);
                    channel.basicNack(message.getMessageProperties().getDeliveryTag(), false,true);
                } catch (IOException ex) {
                    log.error("重新入队失败 {}",voucherOrder);
                }
            }
        });
    }
    @RabbitListener(queues = "dlx.seckill.queue")
    public void handleFailedMessage(Message message, Channel channel, VoucherOrder voucherOrder) {
        log.error("订单处理失败，进入死信队列: {}", voucherOrder);
        // 处理死信消息，如记录日志、通知人工干预等
        try {
            Long userId = voucherOrder.getUserId();
            Long voucherId = voucherOrder.getVoucherId();
            // 示例：记录到数据库或发送警报
            stringRedisTemplate.execute(ROLLBACK_SCRIPT,
                    Arrays.asList(SECKILL_STOCK_PREFIX, SECKILL_ORDER_PREFIX),
                    voucherId.toString(), userId.toString()
            );
            // 设置订单状态为失败
            voucherOrderService.setOrderStatus(voucherOrder.getId(), OrderStatus.FAILED);
            orderTaskExecutor.submit(()->failedVoucherOrderService.save((FailedVoucherOrder) voucherOrder));// 落库
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (IOException e) {
            log.error("订单ack失败: {}", voucherOrder, e);
        }
    }
}
