package com.lanlink.shopping.config;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 全局限流拦截器：基于令牌桶，QPS 由动态配置 ratelimit.qps 控制（<=0 关闭）。
 * 超限返回 429，保护后端在高峰期不被瞬时流量击穿。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final SysConfigService config;
    private final TokenBucket bucket;
    private final ObjectMapper om = new ObjectMapper();

    public RateLimitInterceptor(SysConfigService config) {
        this.config = config;
        int qps = Math.max(1, config.getInt("ratelimit.qps", 100));
        this.bucket = new TokenBucket(qps * 2, qps); // 容量=2秒突发，速率=qps/秒
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        int qps = config.getInt("ratelimit.qps", 100);
        if (qps <= 0) return true; // 关闭限流
        bucket.configure(qps * 2, qps); // 支持热更新阈值
        if (bucket.tryAcquire()) return true;
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(om.writeValueAsString(R.fail(429, "请求过于频繁，请稍后再试")));
        return false;
    }
}
