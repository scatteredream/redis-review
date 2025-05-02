package com.hmdp.config;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Configuration
public class GuavaConfig {

    @Value("${custom.expectedInsertions}")
    private Long expectedInsertions;
    @Value("${custom.fpp}")
    private Double fpp;

    @Bean
    public BloomFilter<Long> shopIdBloomFilter() {
        return BloomFilter.create(Funnels.longFunnel(), expectedInsertions, fpp);
    }

    @Bean
    public BloomFilter<String> stringBloomFilter() {
        return BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), expectedInsertions, fpp);
    }

}
