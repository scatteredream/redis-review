package com.hmdp.utils;

import lombok.Getter;

public enum OrderStatus {
    SUCCESS("success"), PENDING("pending"), FAILED("failed");
    @Getter
    private final String message;
    OrderStatus(String message) {
        this.message = message;
    }
}
