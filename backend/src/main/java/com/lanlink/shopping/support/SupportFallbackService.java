package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 智能客服备用链路编排服务。
 *
 * 职责（对应需求 5）：
 *  - 主客服服务（本地知识库匹配）健康探测与状态维护；
 *  - 主服务不可用时进入备用链路；主服务恢复后【自动切回】原有服务链路；
 *  - 违规模型调用审计计数（供监控告警）。
 *
 * 备用链路行为边界（对应需求 1/2/3）：
 *  - 知识问答在备用态同样只走本地知识库，绝不调用外部模型生成；
 *  - 外部模型仅在「寒暄问候/嘘寒问暖」时润色语气，且经双重权限校验；
 *  - 主服务恢复瞬间，外部模型调用立即被 {@link FallbackPolicyGuard} 全面禁止。
 */
@Service
public class SupportFallbackService {

    private static final Logger log = LoggerFactory.getLogger(SupportFallbackService.class);

    private final SupportAnswerService answers;
    private final ExternalGreetingClient externalGreetingClient;

    /** 备用链路是否激活（主服务不可用时为 true）。 */
    private final AtomicBoolean backupActive = new AtomicBoolean(false);
    /** 违规模型调用计数（监控告警指标）。 */
    private final AtomicLong violations = new AtomicLong();
    /** 本地寒暄模板（外部模型不可用/输出不合格时的兜底，语气自然亲切）。 */
    private static final String[] LOCAL_GREETINGS = {
            "您好！很高兴见到您～ 有什么可以帮您的吗？",
            "您好呀！随时为您服务，请问需要什么帮助？",
            "您好！在这儿呢～ 您想了解哪方面的问题？"
    };
    private final AtomicLong greetingCursor = new AtomicLong();

    public SupportFallbackService(SupportAnswerService answers, ExternalGreetingClient externalGreetingClient) {
        this.answers = answers;
        this.externalGreetingClient = externalGreetingClient;
    }

    /** 主服务健康探测：知识库能否正常应答（每 30s 一次，恢复后自动切回）。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void healthCheck() {
        boolean healthy = isPrimaryHealthy();
        if (healthy && backupActive.get()) {
            backupActive.set(false);
            log.info("[fallback-monitor] 主客服服务已恢复，自动切回原有服务链路（外部模型调用已禁用）");
        } else if (!healthy && !backupActive.get()) {
            backupActive.set(true);
            log.warn("[fallback-monitor] 主客服服务不可用，进入备用链路（知识问答仍只走本地知识库，外部模型仅限寒暄润色）");
        }
    }

    private boolean isPrimaryHealthy() {
        try {
            // 用一条已知知识问题探测本地知识库匹配能力
            String probe = answers.matchedEntryId("如何注册账号");
            return probe != null && !probe.isBlank();
        } catch (Exception e) {
            log.warn("[fallback-monitor] 主服务健康探测异常: {}", e.getMessage());
            return false;
        }
    }

    public boolean isBackupActive() {
        return backupActive.get();
    }

    public long violationCount() {
        return violations.get();
    }

    /**
     * 外部寒暄渠道配置状态：至少配置一个可用的 API Key 才视为已配置
     * （与 externalGreetingClient.hasAnyChannel() 同口径，供监控接口真实反映配置有效性）。
     */
    public boolean externalChannelConfigured() {
        return externalGreetingClient.hasAnyChannel();
    }

    /** 测试钩子：强制设置备用态（不对外暴露）。 */
    void forceBackupActive(boolean active) {
        backupActive.set(active);
    }

    /**
     * 备用链路统一应答入口（供控制器在主服务异常时调用）。
     * 权限校验失败/违规请求 → 记录审计并返回礼貌拒答，绝不触达外部模型。
     */
    public String answerInBackup(String question) {
        FallbackPolicyGuard.Intent intent = FallbackPolicyGuard.classify(question);
        String denial = FallbackPolicyGuard.authorizeExternalGreeting(intent, backupActive.get());
        if (intent == FallbackPolicyGuard.Intent.PROHIBITED) {
            violations.incrementAndGet();
            log.warn("[fallback-policy] 违规模型调用已拦截 #{} (intent=PROHIBITED)", violations.get());
            return politeRejection();
        }
        switch (intent) {
            case KNOWLEDGE -> {
                // 需求 1：知识问答只允许本地知识库，禁止外部模型
                String local = answers.answer(question);
                return local != null ? local : politeRejection();
            }
            case GREETING -> {
                // 需求 2：寒暄关怀允许人性化交互（外部模型润色语气 + 本地模板兜底）
                if (denial == null && externalGreetingClient.hasAnyChannel()) {
                    String polished = externalGreetingClient.polishGreeting(question);
                    if (polished != null) return polished;
                }
                return nextLocalGreeting();
            }
            default -> {
                return politeRejection();
            }
        }
    }

    private String nextLocalGreeting() {
        long idx = greetingCursor.getAndIncrement() % LOCAL_GREETINGS.length;
        return LOCAL_GREETINGS[(int) idx];
    }

    private String politeRejection() {
        return "您好！这里是 LANLinkshopping 客服，当前仅能解答平台知识库已收录的电商问题，"
                + "或与您寒暄问候。请问有什么可以帮您？";
    }
}
