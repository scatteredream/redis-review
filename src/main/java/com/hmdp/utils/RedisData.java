package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

//非侵入性
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
