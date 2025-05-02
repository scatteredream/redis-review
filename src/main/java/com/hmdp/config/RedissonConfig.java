package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    Config config = new Config();

    @Value("${spring.data.redis.host}")
    String host;

    @Value("${spring.data.redis.port}")
    String port;

    @Value("${spring.data.redis.password}")
    String password;
    @Bean
    public RedissonClient redisson() {
        config.useSingleServer().setAddress("redis://"+ host + ":" + port)
                .setDatabase(0).setPassword(password);
        return Redisson.create(config);
    }
}
