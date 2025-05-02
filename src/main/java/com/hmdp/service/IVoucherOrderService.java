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
    Result secKillOrder(Long voucherId);

    Result secKillVoucherOrder(Long voucherId);

    Result createVoucherOrder(Long voucherId);

    boolean createVoucherOrderAsync(VoucherOrder order);
}
