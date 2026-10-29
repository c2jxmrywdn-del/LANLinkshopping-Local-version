package com.lanlink.shopping.module.risk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.risk.entity.UserRiskEvent;
import com.lanlink.shopping.module.risk.entity.UserRiskSummary;
import com.lanlink.shopping.module.risk.mapper.UserRiskEventMapper;
import com.lanlink.shopping.module.risk.mapper.UserRiskSummaryMapper;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端：用户风险名单（/admin/** 由 AuthInterceptor 限平台运营）。
 * 风控只分级提示不自动处置——REVIEW 级别提示"建议人工处理"，处置动作走审计流程。
 */
@RestController
@RequestMapping("/admin/risk")
public class AdminRiskController {

    private final UserRiskEventMapper eventMapper;
    private final UserRiskSummaryMapper summaryMapper;
    private final AuditService auditService;

    public AdminRiskController(UserRiskEventMapper eventMapper, UserRiskSummaryMapper summaryMapper,
                               AuditService auditService) {
        this.eventMapper = eventMapper;
        this.summaryMapper = summaryMapper;
        this.auditService = auditService;
    }

    /** 风险名单（分页，可按级别/最低分过滤） */
    @GetMapping("/users")
    public R<Object> users(@RequestParam(defaultValue = "1") long page,
                           @RequestParam(defaultValue = "20") long size,
                           @RequestParam(required = false) String level,
                           @RequestParam(required = false) Integer minScore) {
        Page<UserRiskSummary> p = summaryMapper.selectPage(new Page<>(page, size),
                Wrappers.<UserRiskSummary>lambdaQuery()
                        .eq(level != null && !level.isBlank(), UserRiskSummary::getLevel, level)
                        .ge(minScore != null, UserRiskSummary::getTotalScore, minScore)
                        .orderByDesc(UserRiskSummary::getTotalScore));
        return R.ok(p);
    }

    /** 单用户命中明细（含已复核） */
    @GetMapping("/users/{userId}/events")
    public R<Object> events(@PathVariable Long userId) {
        return R.ok(eventMapper.selectList(Wrappers.<UserRiskEvent>lambdaQuery()
                .eq(UserRiskEvent::getUserId, userId)
                .orderByDesc(UserRiskEvent::getCreateTime)
                .last("LIMIT 100")));
    }

    /** 人工复核：标记误报（reviewedStatus=0，汇总重算时排除）或确认（=1） */
    @PostMapping("/users/{userId}/review")
    public R<Map<String, Object>> review(@PathVariable Long userId,
                                         @RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        Long reviewer = UserContext.currentUserId(request);
        Long eventId = body.get("eventId") == null ? null : Long.valueOf(String.valueOf(body.get("eventId")));
        int reviewedStatus = body.get("reviewedStatus") == null ? 1
                : Integer.parseInt(String.valueOf(body.get("reviewedStatus")));
        UserRiskEvent e = eventId == null ? null : eventMapper.selectById(eventId);
        if (e == null || !userId.equals(e.getUserId())) return R.fail("事件不存在或与用户不符");
        e.setReviewedStatus(reviewedStatus);
        e.setReviewedBy(reviewer);
        e.setReviewedTime(LocalDateTime.now());
        eventMapper.updateById(e);
        auditService.record(reviewer, "RISK_REVIEW",
                "风控复核 user=" + userId + " event=" + eventId + " status=" + reviewedStatus, request);
        // 即时重算该用户总分（不等周期任务）
        int total = eventMapper.selectList(Wrappers.<UserRiskEvent>lambdaQuery()
                        .eq(UserRiskEvent::getUserId, userId)
                        .and(w -> w.isNull(UserRiskEvent::getReviewedStatus).or().eq(UserRiskEvent::getReviewedStatus, 1)))
                .stream().mapToInt(ev -> ev.getScore() == null ? 0 : ev.getScore()).sum();
        total = Math.min(100, total);
        String level = total >= 70 ? "REVIEW" : total >= 40 ? "WATCH" : "NORMAL";
        summaryMapper.upsert(userId, total, level, 0, LocalDateTime.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalScore", total);
        out.put("level", level);
        return R.ok(out);
    }
}
