package com.lanlink.cloud.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关统一鉴权过滤器（阶段 1 占位实现，阶段 2 升级为 JWT 校验 + X-User-Id 透传）。
 * 公开前缀直接放行；其余请求必须携带 Authorization: Bearer <token>，否则 401。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 公开路径前缀（无需登录） */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/auth/",
            "/api/home/",
            "/api/users/csrf-token",
            "/api/design/auth/"   // 设计工具 OAuth 授权跳转/回调公开（第三方回跳无法携带令牌）
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 公开接口放行
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return chain.filter(exchange);
            }
        }
        // 商品浏览（GET）公开
        if (path.startsWith("/api/products/") && "GET".equalsIgnoreCase(request.getMethod().name())) {
            return chain.filter(exchange);
        }

        // 其余请求必须携带 Bearer token
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || auth.length() <= 7) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        // 阶段 2：校验 JWT 并透传 X-User-Id / X-User-Role
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -100; // 最先执行
    }
}
