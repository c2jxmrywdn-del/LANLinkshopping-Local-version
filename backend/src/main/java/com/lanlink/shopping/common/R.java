package com.lanlink.shopping.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 统一响应封装 R<code,message,data>
 */
@Data
public class R<T> {
    private Integer code;
    private String message;
    private T data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String errorCode;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String requestId;

    public static <T> R<T> ok() { return build(200, "操作成功", null); }
    public static <T> R<T> ok(T data) { return build(200, "操作成功", data); }
    public static <T> R<T> ok(String msg, T data) { return build(200, msg, data); }

    /**
     * 通用业务失败（未显式指定 code 时）。
     *
     * <p>默认 <b>400</b>：绝大多数调用点是"未入驻商户 / 状态不允许 / 业务规则不满足"等
     * 客户端可修正的问题；若继续用 500，前端会统一提示"服务异常，请稍后重试"，把业务提示
     * 掩盖成服务故障。真正的服务端异常由 GlobalExceptionHandler.handleOther 返回 500。
     */
    public static <T> R<T> fail(String msg) { return build(400, msg, null); }
    public static <T> R<T> fail(Integer code, String msg) { return build(code, msg, null); }

    public static <T> R<T> build(Integer code, String message, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(data);
        return r;
    }
}
