package com.lanlink.shopping.common;

/**
 * 业务异常。
 *
 * <p>默认 code 为 <b>400</b>：业务规则/参数/状态校验失败属于"客户端可修正"的错误，
 * 不能伪装成 500 服务端故障（否则前端一律提示"服务异常"，监控也会误报）。
 * 真正的服务端异常走 {@code GlobalExceptionHandler.handleOther}（500）。
 */
public class BusinessException extends RuntimeException {
    private final Integer code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
