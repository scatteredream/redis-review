package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.OrderStatus;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    Result secKillOrderLuaScript(Long voucherId);

    void setOrderStatus(Long orderId, OrderStatus status);

    Result secKillOrderRedisson(Long voucherId);

    Result createOrderById(Long voucherId);

    boolean createOrder(VoucherOrder order);

    OrderStatus getOrderStatus(Long orderId);
}
