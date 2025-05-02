package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {


    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // 声明延迟队列（核心）
    @Bean
    public Queue delaySeckillQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", "hmdianping.direct"); // 死信交换机
        args.put("x-dead-letter-routing-key", "direct.seckill"); // 死信路由键
        args.put("x-message-ttl", 60000); // 队列统一TTL（可选）
        return new Queue("delay.seckill.queue", true, false, false, args); // 持久化队列
    }

    // 声明延迟交换机
    @Bean
    public Exchange delayExchange() {
        return new DirectExchange("hmdianping.delay"); // 根据需求选择交换机类型
    }

    // 绑定队列到交换机
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delaySeckillQueue())
                .to(delayExchange())
                .with("delay.seckill") // 路由键
                .noargs();
    }

}
