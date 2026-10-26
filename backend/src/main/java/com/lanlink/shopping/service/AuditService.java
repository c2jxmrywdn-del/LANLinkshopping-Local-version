package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.AuditLog;
import com.lanlink.shopping.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志服务：关键操作落库（时间/内容/客户端IP/设备信息）。
 * 存储周期不少于 180 天：由后台运维侧保留策略保证（演示环境不自动清理）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogMapper mapper;

    public AuditService(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记录审计日志。审计属于旁路能力：写入失败只记录错误日志，不向上抛出，
     * 避免数据库异常导致主流程（如越权拦截的 403 响应）被中断。
     */
    public void record(Long userId, String action, String detail, HttpServletRequest request) {
        try {
            AuditLog entry = new AuditLog();
            entry.setUserId(userId);
            entry.setAction(action);
            entry.setDetail(trim(detail, 500));
            if (request != null) {
                entry.setClientIp(clientIp(request));
                entry.setUserAgent(trim(request.getHeader("User-Agent"), 255));
            }
            entry.setCreateTime(LocalDateTime.now());
            mapper.insert(entry);
        } catch (RuntimeException e) {
            log.error("[audit] 审计日志写入失败 action={} userId={} detail={}", action, userId, detail, e);
        }
    }

    private String clientIp(HttpServletRequest r) {
        String xff = r.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return r.getRemoteAddr();
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
