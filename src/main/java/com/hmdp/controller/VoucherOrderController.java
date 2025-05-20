package com.hmdp.controller;


import com.google.common.util.concurrent.RateLimiter;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RateLimiterClient;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 前端控制器,主要解决优惠券秒杀
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RateLimiterClient rateLimiterClient;

    @Resource
    private RateLimiter rateLimiter = RateLimiter.create(30.0,10, TimeUnit.SECONDS);

    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        if(rateLimiter.tryAcquire()) {
            // 令牌桶限流
            return Result.fail("sorry, service is too hot, plz try again");
        }
        return voucherOrderService.secKillOrderLuaScript(voucherId);
    }
    @GetMapping("/seckill/status/{orderId}")
    public Result getStatus(@PathVariable("orderId") Long orderId) {
        return Result.ok(voucherOrderService.getOrderStatus(orderId).getMessage()) ;
    }
}
