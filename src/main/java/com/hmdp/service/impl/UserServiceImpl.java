package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
//slf4j 日志记录一下验证码即可
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        } else {
            //TODO 1. 生成验证码
            String code = RandomUtil.randomNumbers(6);
/*
                 保存到session,过期时间由tomcat控制
            session.setAttribute(ATTRIBUTE_NAME_CODE, code);
*/

            //TODO 2. 保存到redis,并手动设置过期时间
            stringRedisTemplate.opsForValue()
                    .set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

            // pretend sending...
            if (log.isDebugEnabled()) {
                log.debug("发送短信验证码成功，验证码为：{}", code);
            }

            return Result.ok(code);
        }
    }

    @Override
    public Result logout(String token) {
        Boolean delete = stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        if (!delete) {
            return Result.ok();
        }
        return Result.ok("登出成功");
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        //TODO 1. 再次验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

/*
           获取session中的验证码，注意类型转换，对象内容比较要用equals，非空判断在左边更合适
        Object cacheCode = session.getAttribute(ATTRIBUTE_NAME_CODE);
        if (cacheCode==null || !cacheCode.toString().equals(code))
*/

        //TODO 2. REDIS + TOKEN 替代 SESSION, 不需要类型转换了
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("输入的验证码无效");
        } else {
            //TODO 3. 按照手机号进行等值查询,mp的服务类父类能快捷获取QueryChainWrapper
            User user = lambdaQuery().eq(User::getPhone, phone).one();
            //TODO 4. 不存在用户则新注册一个用户
            if (user == null) {
                user = createUserWithPhone(phone);
            }
            //TODO 5. 生成随机 token, UUID, 简单模式
            String token = UUID.randomUUID().toString(true);
            String tokenKey = LOGIN_USER_KEY + token;
            //TODO 6. 生成 DTO 并将其封装为 Map !!ClassCastException!!
            //注意stringTemplate要求所有字段为String,Long不能强制转换为String
            //fieldName: id(String) fieldValue:

            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> beanMap = BeanUtil.beanToMap(
                    userDTO, new HashMap<>(),
                    CopyOptions.create()
//                         .setIgnoreNullValue(true) true: 不注入null false: 注入null
//                        自定义FieldValueEditor:优先级高于nullValue
//                        fieldName-字段名 fieldValue-字段值
                            .setFieldValueEditor((String fieldName, Object fieldValue) -> {
                                if (fieldValue == null) {
                                    return "0";
                                }
                                return fieldValue.toString();
                            }));
            //TODO 7. 将 map 存入 Redis
            stringRedisTemplate.opsForHash().putAll(tokenKey, beanMap);
            //TODO 8. 设置有效期
            stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);

//          session.setAttribute(SESSION_ATTRIBUTE_NAME_USER, userDTO);

            return Result.ok(token);
        }
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.getFirst();
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            } else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }


    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
