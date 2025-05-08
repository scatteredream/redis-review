package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.FailedVoucherOrder;
import com.hmdp.mapper.FailedVoucherOrderMapper;
import com.hmdp.service.IFailedVoucherOrderService;
import org.springframework.stereotype.Service;

@Service
public class FailedVoucherOrderServiceImpl extends ServiceImpl<FailedVoucherOrderMapper,FailedVoucherOrder> implements IFailedVoucherOrderService {

}
