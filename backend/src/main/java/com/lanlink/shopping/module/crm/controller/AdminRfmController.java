package com.lanlink.shopping.module.crm.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.module.crm.service.UserRfmService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 管理端 RFM 用户分层（/admin/** 由 AuthInterceptor 限平台运营）。 */
@RestController
@RequestMapping("/admin/crm/rfm")
public class AdminRfmController {

    private final UserRfmService rfmService;
    private final JdbcTemplate jdbc;

    public AdminRfmController(UserRfmService rfmService, JdbcTemplate jdbc) {
        this.rfmService = rfmService;
        this.jdbc = jdbc;
    }

    /** 分层用户列表（分页，可按层过滤） */
    @GetMapping("/users")
    public R<Object> users(@RequestParam(defaultValue = "1") long page,
                           @RequestParam(defaultValue = "20") long size,
                           @RequestParam(required = false) String layer) {
        String where = layer == null || layer.isBlank() ? "" : " WHERE layer='" + layer.replace("'", "") + "'";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_user_rfm" + where, Long.class);
        var rows = jdbc.queryForList("SELECT r.*, u.phone FROM t_user_rfm r LEFT JOIN t_user u ON u.user_id=r.user_id" +
                where + " ORDER BY m_amount DESC LIMIT " + Math.min(Math.max(size, 1), 100) +
                " OFFSET " + Math.max((page - 1) * size, 0));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", total);
        return R.ok(out);
    }

    /** 分层概览（各层人数与金额贡献） */
    @GetMapping("/summary")
    public R<Object> summary() {
        return R.ok(rfmService.layerSummary());
    }

    /** 手动触发重算（运营调阈值后立即可见） */
    @PostMapping("/rebuild")
    public R<Void> rebuild() {
        rfmService.rebuild();
        return R.ok("已重算", null);
    }
}
