package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

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

    Result secKillOrderRedisson(Long voucherId);

    Result createOrderById(Long voucherId);

    boolean createOrder(VoucherOrder order);
}
