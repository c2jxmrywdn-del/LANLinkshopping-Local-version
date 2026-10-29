package com.lanlink.shopping.module.risk.service;

import com.lanlink.shopping.module.risk.mapper.UserRiskEventMapper;
import com.lanlink.shopping.module.risk.mapper.UserRiskSummaryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 风控周期任务（每 30 分钟）：
 *  1. 规则探测器扫描库内模式 → 命中即落 t_user_risk_event（24h 去重）；
 *  2. 汇总物化：按用户聚合未复核+确认事件分值（封顶 100）→ 阈值分级 NORMAL/WATCH/REVIEW。
 * 规则表：R01 高频取消(30) R02 高频下单(20,挂 checkout) R03 批量下单(15) R04 重复退款(30)
 *         R05 篡改(25,挂 TamperGuard) R06 登录异常(10) R07 客服灌水(5,预留)。
 * 只记录与分级提示，不做自动封禁（人工处置走审计流程）。
 */
@Component
public class RiskSummaryJob {

    private static final Logger log = LoggerFactory.getLogger(RiskSummaryJob.class);

    private final UserRiskEventMapper eventMapper;
    private final UserRiskSummaryMapper summaryMapper;
    private final RiskEventService riskEventService;

    public RiskSummaryJob(UserRiskEventMapper eventMapper, UserRiskSummaryMapper summaryMapper,
                          RiskEventService riskEventService) {
        this.eventMapper = eventMapper;
        this.summaryMapper = summaryMapper;
        this.riskEventService = riskEventService;
    }

    @Scheduled(cron = "0 */30 * * * ?")
    public void detectAndSummarize() {
        try {
            detect();
        } catch (Exception e) {
            log.warn("[risk] detect failed: {}", e.getMessage());
        }
        try {
            summarize();
        } catch (Exception e) {
            log.warn("[risk] summarize failed: {}", e.getMessage());
        }
    }

    private void detect() {
        for (Map<String, Object> r : eventMapper.detectHighCancelUsers()) {
            riskEventService.record(Long.valueOf(String.valueOf(r.get("userId"))), "R01", 30,
                    "7 天取消 " + r.get("cancels") + " 单，取消率 " + r.get("cancelRate"), "order", null);
        }
        for (Map<String, Object> r : eventMapper.detectBulkOrderUsers()) {
            riskEventService.record(Long.valueOf(String.valueOf(r.get("userId"))), "R03", 15,
                    "24h 内同商品下单 " + r.get("times") + " 次/数量 " + r.get("qty"),
                    "product", String.valueOf(r.get("prodId")));
        }
        for (Map<String, Object> r : eventMapper.detectRefundAbuseUsers()) {
            riskEventService.record(Long.valueOf(String.valueOf(r.get("userId"))), "R04", 30,
                    "90 天退款申请/成功 " + r.get("refunds") + " 次", "order", null);
        }
        for (Map<String, Object> r : eventMapper.detectLoginAnomalyUsers()) {
            riskEventService.record(Long.valueOf(String.valueOf(r.get("userId"))), "R06", 10,
                    "同 IP 10 分钟登录失败 " + r.get("cnt") + " 次", "login", null);
        }
    }

    /** 汇总物化：sum(未复核+确认事件分) 封顶 100；>=70 REVIEW、>=40 WATCH，否则 NORMAL */
    private void summarize() {
        List<Map<String, Object>> rows = eventMapper.aggregateByUser();
        eventMapper.clearSummary();
        for (Map<String, Object> r : rows) {
            long userId = Long.parseLong(String.valueOf(r.get("user_id")));
            int total = (int) Math.min(100, Long.parseLong(String.valueOf(r.get("total"))));
            int hits = Integer.parseInt(String.valueOf(r.get("hits")));
            LocalDateTime last = r.get("last_time") == null ? null
                    : LocalDateTime.parse(String.valueOf(r.get("last_time")).replace(' ', 'T'));
            String level = total >= 70 ? "REVIEW" : total >= 40 ? "WATCH" : "NORMAL";
            summaryMapper.upsert(userId, total, level, hits, last);
        }
        log.info("[risk] summary materialized: {} users", rows.size());
    }
}
