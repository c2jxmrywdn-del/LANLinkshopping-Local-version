package com.lanlink.shopping.module.risk.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.risk.entity.UserRiskEvent;
import com.lanlink.shopping.module.risk.mapper.UserRiskEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 风控事件落库（旁路）：任何业务挂钩点调用本方法都必须不影响主流程——
 * 内部全量 try-catch，失败仅记日志。规则集与分值见 RiskSummaryJob 规则表注释。
 */
@Service
public class RiskEventService {

    private static final Logger log = LoggerFactory.getLogger(RiskEventService.class);

    private final UserRiskEventMapper eventMapper;

    @Value("${lanlink.risk.enabled:true}")
    private boolean enabled;

    public RiskEventService(UserRiskEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /** 匿名事件哨兵 user_id（t_user_risk_event.user_id NOT NULL）：0 = 未登录/无凭据主体 */
    static final long ANONYMOUS_USER_ID = 0L;

    /**
     * 记录一次命中。userId 为空时以 {@link #ANONYMOUS_USER_ID} 哨兵落库（A12④：匿名滥用——
     * AI 刷量/匿名爬取/接口滥用等——也要留风控痕），主体身份标识放 refId（visitorToken/sessionId/IP）。
     * 注意：匿名事件按 (0, ruleCode) 全局去重，24h 内同规则匿名只记一条，避免刷量打分膨胀。
     */
    public void record(Long userId, String ruleCode, int score, String detail, String refType, String refId) {
        if (!enabled) return;
        long effectiveUserId = userId == null ? ANONYMOUS_USER_ID : userId;
        try {
            // 同用户同规则 24h 内只保留一条未复核命中（检测器幂等 + 打分不重复膨胀）
            if (eventMapper.countRecentUnreviewed(effectiveUserId, ruleCode) > 0) return;
            UserRiskEvent e = new UserRiskEvent();
            e.setUserId(effectiveUserId);
            e.setRuleCode(ruleCode);
            e.setScore(score);
            e.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)));
            e.setRefType(refType);
            e.setRefId(refId == null ? null : refId.substring(0, Math.min(refId.length(), 40)));
            eventMapper.insert(e);
            log.info("[risk] hit: user={} rule={} score={} ref={}/{}", effectiveUserId, ruleCode, score, refType, refId);
        } catch (Exception ex) {
            log.warn("[risk] record failed (ignored): user={} rule={} err={}", effectiveUserId, ruleCode, ex.getMessage());
        }
    }

    /** 查询用户未复核事件总数（供业务侧展示提示，不阻断） */
    public long unreviewedCount(Long userId) {
        if (userId == null) return 0;
        try {
            return eventMapper.selectCount(Wrappers.<UserRiskEvent>lambdaQuery()
                    .eq(UserRiskEvent::getUserId, userId)
                    .isNull(UserRiskEvent::getReviewedStatus));
        } catch (Exception e) {
            return 0;
        }
    }
}
