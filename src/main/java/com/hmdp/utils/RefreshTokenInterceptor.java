package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;

    //TODO 1. 拦截器未被容器接管,不能依赖注入,以有参构造方式从配置类传入
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //TODO 2. 获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(tokenKey);
        if (entries.isEmpty()) {
            return true;
        }
        //为了减小传输量，使用更加精简的UserDTO在服务器内部传递信息
        //TODO 3. 将map转成DTO保存到ThreadLocal中
        UserDTO cacheUser = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);
        UserHolder.saveUser(cacheUser);
        //TODO 4. 刷新Redis过期时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        return true;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
