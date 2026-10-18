package com.lanlink.shopping.social;

import java.util.Map;

/**
 * 社交登录出网访问的抽象层。
 *
 * 抽成接口的目的：各平台的换取逻辑可注入假实现做单测，
 * 无需真实凭证与公网即可覆盖「请求构造 + 响应解析 + 错误分支」。
 */
public interface SocialHttp {

    /** 表单 POST（application/x-www-form-urlencoded） */
    String postForm(String url, Map<String, String> form, Map<String, String> headers);

    /** GET（可带自定义头，如 Authorization） */
    String get(String url, Map<String, String> headers);

    /**
     * 带自定义超时的 GET。
     * 默认实现忽略超时参数（由实现类覆盖），便于调用方按场景收紧超时（如地理提示的 900ms）。
     */
    default String get(String url, Map<String, String> headers, long timeoutMs) {
        return get(url, headers);
    }
}
