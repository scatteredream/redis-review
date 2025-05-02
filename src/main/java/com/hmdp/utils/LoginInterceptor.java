package com.hmdp.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        Object cacheUser = request.getSession().getAttribute(SESSION_ATTRIBUTE_NAME_USER);
//        if (cacheUser == null)
        //TODO 1. 查询ThreadLocal是否有UserDTO
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
