package com.hmdp.config;


import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.io.IOException;


@Component
@Slf4j
public class RabbitMQListener {
    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ThreadPoolTaskExecutor orderTaskExecutor;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "direct.seckill.queue",
                    durable = "true"
            ),
            key = "direct.seckill",
            exchange = @Exchange(name = "hmdianping.direct")
    ))
    public void receiveMessage(Message message, Channel channel, VoucherOrder voucherOrder) {
        orderTaskExecutor.submit(() -> {
            log.info("收到订单: {}", voucherOrder);
            Object header = message.getMessageProperties().getHeader("retry-count");
            int retry = Integer.parseInt((String) header);
            if(retry > 3){
                log.error("订单处理失败，超过最大重试次数: 3  {}", voucherOrder);
                log.warn("发送到延迟队列...{}",voucherOrder);
                message.getMessageProperties().setHeader("retry-count", "0");
                rabbitTemplate.send("delay.seckill", "delay.seckill", message);
                log.warn("发送完成 {}",voucherOrder);
                return;
            }
            try{
                boolean success = voucherOrderService.createVoucherOrderAsync(voucherOrder);
                if(success){
                    channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                }
                else
                    throw new RuntimeException("数据库处理异常");
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
    @RabbitListener(queues = "delay.seckill.queue")
    public void handleFailedMessage(Message message, Channel channel, VoucherOrder voucherOrder) {
        log.error("订单处理失败，进入延迟队列: {}", voucherOrder);
        // 处理死信消息，如记录日志、通知人工干预等
        try {
            // 示例：记录到数据库或发送警报
            channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
        } catch (IOException e) {
            log.error("订单重路由失败: {}", voucherOrder, e);
        }
    }
}
