package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 拦截器顺序：根据order排序, 如果order相同就按照添加顺序排序
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //TODO 1. 第一个拦截器需要获取token查redis,查到则保存到ThreadLocal,查不到也放行
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**");
        //TODO 2. 第二个拦截器只需要查ThreadLocal不需要查redis,根据结果放行
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/blog/hot",
                        "/user/code",
                        "/user/login",
                        "/shop-type/**",
                        "/upload/**",
                        "/shop/**",
                        "/voucher/**");
    }
}
