package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.AuditLog;
import com.lanlink.shopping.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志服务：关键操作落库（时间/内容/客户端IP/设备信息）。
 * 存储周期不少于 180 天：由后台运维侧保留策略保证（演示环境不自动清理）。
 */
@Service
public class AuditService {

    private final AuditLogMapper mapper;

    public AuditService(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(Long userId, String action, String detail, HttpServletRequest request) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setDetail(trim(detail, 500));
        if (request != null) {
            log.setClientIp(clientIp(request));
            log.setUserAgent(trim(request.getHeader("User-Agent"), 255));
        }
        log.setCreateTime(LocalDateTime.now());
        mapper.insert(log);
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