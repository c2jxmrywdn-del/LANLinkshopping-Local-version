package com.lanlink.shopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.mapper.UserHabitMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户习惯画像（分析类 Cookie 收集的服务端同步）。
 *
 * <p>采集侧由前端 analytics 授权门控（未授权不记录、不上报）；本控制器只做
 * 登录用户的画像副本管理：上报（upsert，8KB 上限）、查询、清除（注销数据权利）。
 * 载荷仅含浏览偏好（搜索词/类目/最近浏览/访问次数），不含凭证与敏感字段。
 */
@RestController
@RequestMapping("/user/habits")
public class UserHabitController {

    private static final Logger log = LoggerFactory.getLogger(UserHabitController.class);
    /** 画像载荷上限：Cookie 侧本身受条目数限制（搜索词 10/类目 8/最近浏览 12），此处防滥用兜底 */
    private static final int MAX_PAYLOAD_CHARS = 8 * 1024;

    private final UserHabitMapper habitMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserHabitController(UserHabitMapper habitMapper) {
        this.habitMapper = habitMapper;
    }

    /** 上报本机画像（登录用户跨设备同步；节流由前端负责） */
    @PostMapping("/report")
    public R<Map<String, Object>> report(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) return R.fail(401, "请先登录");
        Object raw = body == null ? null : body.get("payload");
        String payload;
        try {
            payload = raw == null ? null : objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new BusinessException("画像载荷无法序列化");
        }
        if (payload == null || payload.length() > MAX_PAYLOAD_CHARS) {
            throw new BusinessException("画像载荷缺失或超出上限");
        }
        habitMapper.upsert(userId, payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("synced", true);
        out.put("chars", payload.length());
        return R.ok(out);
    }

    /** 查询服务端画像副本（跨设备续用） */
    @GetMapping
    public R<Map<String, Object>> get(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) return R.fail(401, "请先登录");
        String payload = habitMapper.findPayload(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", payload != null);
        out.put("payload", payload == null ? null : readTree(payload));
        return R.ok(out);
    }

    /** 清除服务端画像副本（用户行使删除权；本机 Cookie 由前端同步清除） */
    @DeleteMapping
    public R<Boolean> clear(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) return R.fail(401, "请先登录");
        int rows = habitMapper.deleteByUser(userId);
        log.info("[user-habit] cleared server copy, userId={}, rows={}", userId, rows);
        return R.ok("已清除", true);
    }

    private Object readTree(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }
}
