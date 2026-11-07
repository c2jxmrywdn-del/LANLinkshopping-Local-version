package com.lanlink.shopping.module.aitool.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * AI 工具 API 专用异常边界。
 *
 * <p>旧人机接口继续维持 HTTP 200 + R.code；外部 Agent 必须收到真实的
 * 401/403/429/503，同时响应体仍保留 R 包裹，兼容既有 OpenAPI 客户端。</p>
 */
@Order(0)
@RestControllerAdvice(assignableTypes = AiToolsInternalController.class)
public class AiToolExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<R<Void>> handleResponseStatus(ResponseStatusException e,
                                                       jakarta.servlet.http.HttpServletResponse servletResponse) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        R<Void> body = R.fail(status.value(), safeReason(e, status));
        body.setErrorCode(errorCode(status));
        String existing = servletResponse.getHeader("X-Request-Id");
        boolean alreadySet = existing != null && !existing.isBlank();
        String requestId = alreadySet ? existing : UUID.randomUUID().toString();
        body.setRequestId(requestId);
        // 控制器已写入过请求号时不再重复添加，否则 ResponseEntity 会以 addHeader 追加成两个值
        ResponseEntity.BodyBuilder builder = alreadySet
                ? ResponseEntity.status(status)
                : ResponseEntity.status(status).header("X-Request-Id", requestId);
        if (status == HttpStatus.UNAUTHORIZED) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer realm=\"lanlink-ai-tools\", error=\"invalid_token\"");
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE) {
            builder.header(HttpHeaders.RETRY_AFTER, status == HttpStatus.TOO_MANY_REQUESTS ? "5" : "30");
        }
        return builder.body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e,
                                                 jakarta.servlet.http.HttpServletResponse servletResponse) {
        String message = e.getMessage() == null ? "请求失败" : e.getMessage();
        if (message.startsWith("401 ") || message.startsWith("FORBIDDEN:")
                || message.startsWith("429 ") || message.startsWith("503 ")) {
            HttpStatus status = message.startsWith("401 ") ? HttpStatus.UNAUTHORIZED
                    : message.startsWith("429 ") ? HttpStatus.TOO_MANY_REQUESTS
                    : message.startsWith("503 ") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
            return handleResponseStatus(new ResponseStatusException(status, message), servletResponse);
        }
        R<Void> body = R.fail(e.getCode(), message);
        body.setRequestId(currentRequestId(servletResponse));
        return ResponseEntity.ok(body);
    }

    private static String currentRequestId(jakarta.servlet.http.HttpServletResponse servletResponse) {
        String existing = servletResponse.getHeader("X-Request-Id");
        return (existing == null || existing.isBlank()) ? UUID.randomUUID().toString() : existing;
    }

    private static String safeReason(ResponseStatusException e, HttpStatus status) {
        if (status == HttpStatus.UNAUTHORIZED) return "未授权：缺少或错误的 API Key";
        if (status == HttpStatus.FORBIDDEN) return "权限不足：当前 API Key 无权调用该工具";
        if (status == HttpStatus.TOO_MANY_REQUESTS) return "请求频率超限，请稍后重试";
        if (status == HttpStatus.SERVICE_UNAVAILABLE) return "本地知识库暂时不可用";
        String reason = e.getReason();
        return reason == null || reason.isBlank() ? status.getReasonPhrase() : reason;
    }

    private static String errorCode(HttpStatus status) {
        return switch (status.value()) {
            case 401 -> "AUTH_KEY_INVALID";
            case 403 -> "ACL_FORBIDDEN";
            case 429 -> "RATE_LIMIT_EXCEEDED";
            case 503 -> "DEPENDENCY_UNAVAILABLE";
            default -> "INTERNAL_ERROR";
        };
    }
}
