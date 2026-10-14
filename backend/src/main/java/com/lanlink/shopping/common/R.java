package com.lanlink.shopping.common;

import lombok.Data;

/**
 * 统一响应封装 R<code,message,data>
 */
@Data
public class R<T> {
    private Integer code;
    private String message;
    private T data;

    public static <T> R<T> ok() { return build(200, "操作成功", null); }
    public static <T> R<T> ok(T data) { return build(200, "操作成功", data); }
    public static <T> R<T> ok(String msg, T data) { return build(200, msg, data); }
    public static <T> R<T> fail(String msg) { return build(500, msg, null); }
    public static <T> R<T> fail(Integer code, String msg) { return build(code, msg, null); }

    public static <T> R<T> build(Integer code, String message, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(data);
        return r;
    }
}
