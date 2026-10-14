package com.lanlink.shopping.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.entity.AuditLog;
import com.lanlink.shopping.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 管理后台审计日志：分页查询 + CSV 导出（AuthInterceptor 已限定 admin 角色）。
 * 日志不可篡改：无编辑/删除接口，仅查询与导出。
 */
@RestController
@RequestMapping("/admin/audit")
public class AdminAuditController {

    private final AuditLogMapper auditLogMapper;

    public AdminAuditController(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @GetMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) Long userId,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false) String beginDate,
                                       @RequestParam(required = false) String endDate) {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (userId != null) qw.eq(AuditLog::getUserId, userId);
        if (action != null && !action.isBlank()) qw.eq(AuditLog::getAction, action.trim());
        if (beginDate != null && !beginDate.isBlank()) {
            qw.ge(AuditLog::getCreateTime, LocalDate.parse(beginDate).atStartOfDay());
        }
        if (endDate != null && !endDate.isBlank()) {
            qw.le(AuditLog::getCreateTime, LocalDate.parse(endDate).atTime(LocalTime.MAX));
        }
        qw.orderByDesc(AuditLog::getId);
        IPage<AuditLog> result = auditLogMapper.selectPage(new Page<>(page, size), qw);
        return R.ok(Map.of(
                "records", result.getRecords(),
                "total", result.getTotal(),
                "page", result.getCurrent(),
                "size", result.getSize()));
    }

    /** 按当前筛选项导出全部（含 BOM，Excel 直接打开中文不乱码） */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long userId,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String beginDate,
                       @RequestParam(required = false) String endDate,
                       HttpServletResponse response) throws IOException {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (userId != null) qw.eq(AuditLog::getUserId, userId);
        if (action != null && !action.isBlank()) qw.eq(AuditLog::getAction, action.trim());
        if (beginDate != null && !beginDate.isBlank()) qw.ge(AuditLog::getCreateTime, LocalDate.parse(beginDate).atStartOfDay());
        if (endDate != null && !endDate.isBlank()) qw.le(AuditLog::getCreateTime, LocalDate.parse(endDate).atTime(LocalTime.MAX));
        qw.orderByAsc(AuditLog::getId);
        List<AuditLog> list = auditLogMapper.selectList(qw);

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=audit_log_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv");
        OutputStreamWriter out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        out.write('\uFEFF'); // UTF-8 BOM
        out.write("ID,操作时间,用户ID,操作类型,操作内容,客户端IP,设备信息\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (AuditLog l : list) {
            out.write(String.join(",",
                    String.valueOf(l.getId()),
                    l.getCreateTime() == null ? "" : l.getCreateTime().format(fmt),
                    String.valueOf(l.getUserId()),
                    csv(l.getAction()),
                    csv(l.getDetail()),
                    csv(l.getClientIp()),
                    csv(l.getUserAgent())) + "\n");
        }
        out.flush();
    }

    private String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }
}