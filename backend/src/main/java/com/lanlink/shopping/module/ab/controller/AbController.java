package com.lanlink.shopping.module.ab.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.ab.service.AbStatService;
import com.lanlink.shopping.module.ab.service.AbTestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A/B 实验 API。
 *  - GET  /ab/assign?exp=            分流（公开：登录用 userId，匿名用 sessionId）
 *  - POST /ab/expose|convert         曝光/转化日志（公开；前端在渲染点/支付钩子调用）
 *  - GET  /ab/result?exp=            实验结果（管理端只读也放开只读统计，敏感度低）
 *  - /admin/ab/**                    实验 CRUD（平台运营）
 */
@RestController
public class AbController {

    private final AbTestService abTestService;
    private final AbStatService abStatService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AbController(AbTestService abTestService, AbStatService abStatService,
                        org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.abTestService = abTestService;
        this.abStatService = abStatService;
        this.jdbc = jdbc;
    }

    @GetMapping("/ab/assign")
    public R<Map<String, Object>> assign(@RequestParam String exp, HttpServletRequest request) {
        Long userId = currentUserIdOrNull(request);
        String sessionId = request.getHeader("X-Session-Id");
        var a = abTestService.assign(exp, userId, sessionId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("expKey", exp);
        out.put("variant", a == null ? null : a.variant());
        out.put("bucketBy", userId != null ? "userId" : "sessionId");
        return R.ok(out);
    }

    @PostMapping("/ab/expose")
    public R<Void> expose(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = currentUserIdOrNull(request);
        abTestService.expose(str(body.get("expKey")), str(body.get("variant")), userId,
                str(body.get("sessionId")));
        return R.ok(null);
    }

    @PostMapping("/ab/convert")
    public R<Void> convert(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = currentUserIdOrNull(request);
        BigDecimal value = body.get("value") == null ? null : new BigDecimal(String.valueOf(body.get("value")));
        abTestService.convert(str(body.get("expKey")), str(body.get("variant")), userId,
                str(body.get("sessionId")), value);
        return R.ok(null);
    }

    @GetMapping("/ab/result")
    public R<Map<String, Object>> result(@RequestParam String exp) {
        return R.ok(abStatService.result(exp));
    }

    // ------------------------------ 管理端（/admin/** 由 AuthInterceptor 限运营） ------------------------------

    @GetMapping("/admin/ab/experiments")
    public R<Object> list() {
        return R.ok(jdbc.queryForList(
                "SELECT id, exp_key expKey, name, status, traffic_pct trafficPct, variants_json variantsJson, " +
                        " metric, description, create_time createTime FROM t_ab_experiment ORDER BY id DESC"));
    }

    @PostMapping("/admin/ab/experiments")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String expKey = str(body.get("expKey"));
        List<?> variants = (List<?>) body.get("variants");
        if (expKey == null || expKey.isBlank() || variants == null || variants.size() < 2) {
            return R.fail("expKey 与至少两个变体必填");
        }
        int pct = body.get("trafficPct") == null ? 100 : Integer.parseInt(String.valueOf(body.get("trafficPct")));
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(variants);
        } catch (Exception e) {
            return R.fail("变体序列化失败");
        }
        jdbc.update("INSERT INTO t_ab_experiment(exp_key, name, status, traffic_pct, variants_json, metric, description) " +
                        "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name), traffic_pct=VALUES(traffic_pct), " +
                        "variants_json=VALUES(variants_json), metric=VALUES(metric), description=VALUES(description)",
                expKey, str(body.get("name")) == null ? expKey : str(body.get("name")),
                str(body.get("status")) == null ? "running" : str(body.get("status")),
                Math.min(Math.max(pct, 0), 100), json,
                str(body.get("metric")) == null ? "ctr" : str(body.get("metric")),
                str(body.get("description")));
        return R.ok(Map.of("expKey", expKey));
    }

    /** 状态机：draft/running/paused/finished（running 才分流） */
    @PostMapping("/admin/ab/experiments/{expKey}/status")
    public R<Void> setStatus(@PathVariable String expKey, @RequestBody Map<String, Object> body) {
        String status = str(body.get("status"));
        if (!List.of("draft", "running", "paused", "finished").contains(status)) {
            return R.fail("非法状态");
        }
        jdbc.update("UPDATE t_ab_experiment SET status=? WHERE exp_key=?", status, expKey);
        return R.ok(null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Long currentUserIdOrNull(HttpServletRequest request) {
        try {
            return UserContext.currentUserId(request);
        } catch (Exception e) {
            return null;
        }
    }
}
