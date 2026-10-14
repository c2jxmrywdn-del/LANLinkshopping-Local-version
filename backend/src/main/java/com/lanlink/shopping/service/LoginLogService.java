package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.entity.LoginLog;
import com.lanlink.shopping.mapper.LoginLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录安全记录服务：登录成功/失败落库（时间/IP/设备）
 */
@Service
public class LoginLogService {

    private final LoginLogMapper mapper;

    public LoginLogService(LoginLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(Long userId, String phone, boolean success, String reason, HttpServletRequest request) {
        LoginLog log = new LoginLog();
        log.setUserId(userId);
        // t_login_log.phone is VARCHAR(20); malformed input must not break failure auditing.
        log.setPhone(trim(phone, 20));
        log.setSuccess(success ? 1 : 0);
        log.setReason(reason == null ? "" : reason);
        if (request != null) {
            log.setClientIp(clientIp(request));
            log.setUserAgent(trim(request.getHeader("User-Agent"), 255));
        }
        log.setCreateTime(LocalDateTime.now());
        mapper.insert(log);
    }

    /** 本人登录记录分页（倒序） */
    public Map<String, Object> page(Long userId, long page, long size) {
        IPage<LoginLog> result = mapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<LoginLog>().eq(LoginLog::getUserId, userId).orderByDesc(LoginLog::getId));
        Map<String, Object> out = new HashMap<>();
        out.put("records", result.getRecords());
        out.put("total", result.getTotal());
        out.put("page", result.getCurrent());
        out.put("size", result.getSize());
        return out;
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
