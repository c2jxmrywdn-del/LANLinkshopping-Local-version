package com.lanlink.shopping.common;

/**
 * 业务异常
 */
public class BusinessException extends RuntimeException {
    private final Integer code;

    public BusinessException(String message) {
        this(500, message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
