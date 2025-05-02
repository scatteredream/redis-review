package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
    //minTime 上次查询的最小值 offset从上次查询最小值偏移的个数(上次最小值有多少个)
    //
}
