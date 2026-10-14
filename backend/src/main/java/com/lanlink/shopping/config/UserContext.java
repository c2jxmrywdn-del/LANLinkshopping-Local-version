package com.lanlink.shopping.config;

import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 当前登录用户上下文（基于 HttpSession）+ 请求级身份缓存。
 */
public class UserContext {
    public static final String SESSION_KEY = "LOGIN_USER";
    /** AuthInterceptor 每次请求实时识别后写入的身份 */
    public static final String IDENTITY_KEY = "LL_IDENTITY";

    public static User current(HttpServletRequest request) {
        if (request == null) return null;
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object obj = session.getAttribute(SESSION_KEY);
        return obj instanceof User ? (User) obj : null;
    }

    public static Long currentUserId(HttpServletRequest request) {
        User u = current(request);
        return u == null ? null : u.getUserId();
    }

    /** 当前请求的身份（由 AuthInterceptor 识别；未识别时按 session 兜底，绝不返回 null） */
    public static UserIdentity identity(HttpServletRequest request) {
        Object obj = request == null ? null : request.getAttribute(IDENTITY_KEY);
        if (obj instanceof UserIdentity) return (UserIdentity) obj;
        // 兜底：未登录 → GUEST；已登录但身份属性缺失 → 按普通用户（BUYER）处理，
        // 保证调用方 identity.has() 永远不为 null 触发 NPE。
        return current(request) == null ? UserIdentity.GUEST : UserIdentity.BUYER;
    }
}
