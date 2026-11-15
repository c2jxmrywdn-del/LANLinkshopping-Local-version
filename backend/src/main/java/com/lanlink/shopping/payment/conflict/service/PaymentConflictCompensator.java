package com.lanlink.shopping.payment.conflict.service;

import com.lanlink.shopping.payment.conflict.entity.PaymentConflict;
import com.lanlink.shopping.payment.conflict.mapper.PaymentConflictMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付收款冲突自动补偿 job（P0-B）：扫描"渠道已扣款、订单已关闭"的挂账，
 * 逐笔原路退款；失败按 1m→24h 退避重试，8 次仍失败转人工处置。
 *
 * <p>写法对齐 {@code service/OrderTimeoutCloser}：
 * <ul>
 *   <li>{@link #sweep()} 只做调度，时间从 {@link #drain(LocalDateTime)} 注入，单测不依赖真实时钟；</li>
 *   <li>单轮上限 {@code batchSize}（默认 50），避免一次扫描持有过多行；</li>
 *   <li>逐条 try/catch 异常隔离，一条失败不拖垮整轮；</li>
 *   <li>并发安全落在 {@code PaymentConflictMapper} 的条件更新上：多实例同扫到同一行时，
 *       只有抢单（OPEN/FAILED → AUTO_REFUNDING）成功的一方去调渠道。</li>
 * </ul>
 *
 * <p>"逐条独立事务"由三层组成：{@code claimForCompensation}（抢单）→
 * {@link PaymentConflictService#attemptRefund}（渠道调用，事务外）→
 * {@code finishRefunded/finishFailed}（收尾），三段均为 {@code REQUIRES_NEW}，
 * 渠道 HTTP 调用不会包在数据库事务里。
 *
 * <p>指标：{@code lanlink.payment.conflict.compensate{outcome}}，
 * outcome ∈ refunded / failed / to_manual / skipped / stale / stale_recovered / exception。
 */
@Component
public class PaymentConflictCompensator {

    private static final Logger log = LoggerFactory.getLogger(PaymentConflictCompensator.class);

    private final PaymentConflictMapper mapper;
    private final PaymentConflictService conflictService;
    private final MeterRegistry registry;

    @Value("${app.payment.conflict-auto-refund-enabled:true}")
    private boolean enabled = true;

    @Value("${app.payment.conflict-batch:50}")
    private int batchSize = 50;

    @Value("${app.payment.conflict-scan-window-minutes:1440}")
    private long scanWindowMinutes = 1440;

    /** AUTO_REFUNDING 超过该分钟数未更新视为进程崩溃留下的僵死抢单，放回 FAILED 重试 */
    @Value("${app.payment.conflict-stale-claim-minutes:10}")
    private int staleClaimMinutes = 10;

    public PaymentConflictCompensator(PaymentConflictMapper mapper,
                                      PaymentConflictService conflictService,
                                      MeterRegistry registry) {
        this.mapper = mapper;
        this.conflictService = conflictService;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${app.payment.conflict-compensate-interval-ms:60000}",
            initialDelayString = "${app.payment.conflict-compensate-initial-delay-ms:30000}")
    public void sweep() {
        drain(LocalDateTime.now());
    }

    /**
     * 可注入时间的扫描入口（单测直接传固定 now）。
     *
     * @return 本轮真正完成原路退款的挂账数
     */
    public int drain(LocalDateTime now) {
        if (!enabled) {
            return 0;
        }
        // 退款网关未接线（本骨架轮次）：整轮跳过，避免把挂账空转到 FAILED/MANUAL
        if (!conflictService.canAutoRefund()) {
            log.debug("[PAYMENT] 收款冲突补偿网关未接线，本轮跳过");
            return 0;
        }

        recoverStaleClaims(now);

        List<PaymentConflict> candidates;
        try {
            candidates = mapper.selectOpenBatch(now, now.minusMinutes(scanWindowMinutes), batchSize);
        } catch (Exception e) {
            // 扫描失败不抛：调度线程继续下一轮，避免整个任务被停掉
            log.error("[PAYMENT] 收款冲突补偿扫描失败: {}", e.getMessage(), e);
            return 0;
        }
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int refunded = 0;
        for (PaymentConflict candidate : candidates) {
            try {
                // 1) 抢单（独立事务）：OPEN/FAILED → AUTO_REFUNDING
                PaymentConflict claimed = conflictService.claimForCompensation(candidate.getConflictId(), now);
                if (claimed == null) {
                    // 已被其它实例/前一轮/人工抢走：正常竞态，不是失败
                    counter("skipped");
                    continue;
                }
                // 2) 调渠道（事务外，失败收敛为 RefundAttempt.failure）
                PaymentConflictService.RefundAttempt attempt = conflictService.attemptRefund(claimed);
                if (attempt.ok()) {
                    // 3a) 收尾成功（独立事务）
                    if (conflictService.finishRefunded(claimed.getConflictId(), attempt.refundId())) {
                        refunded++;
                    }
                } else {
                    // 3b) 收尾失败（独立事务）：退避重试或转人工
                    conflictService.finishFailed(claimed.getConflictId(), attempt.message(), now);
                }
            } catch (Exception e) {
                // 单条失败不拖垮整轮：抢单/收尾的事务已回滚，该行仍在 OPEN/FAILED/AUTO_REFUNDING，
                // AUTO_REFUNDING 会由 recoverStaleClaims 在超时后放回重试
                counter("exception");
                log.warn("[PAYMENT] 收款冲突补偿异常，留待下一轮 conflictId={} orderNo={}",
                        candidate.getConflictId(), candidate.getOrderNo(), e);
            }
        }
        if (refunded > 0) {
            log.info("[PAYMENT] 本轮收款冲突自动原路退款 {} 笔（候选 {}）", refunded, candidates.size());
        }
        return refunded;
    }

    /** 回收僵死 AUTO_REFUNDING（进程崩溃留下的抢单），放回 FAILED 立即可重试 */
    private void recoverStaleClaims(LocalDateTime now) {
        try {
            int recovered = mapper.recoverStaleClaims(staleClaimMinutes);
            if (recovered > 0) {
                counter("stale_recovered", recovered);
                log.warn("[PAYMENT] 回收僵死 AUTO_REFUNDING 挂账 {} 笔（超过 {} 分钟），已放回重试",
                        recovered, staleClaimMinutes);
            }
        } catch (Exception e) {
            log.warn("[PAYMENT] 回收僵死 AUTO_REFUNDING 失败（不影响本轮补偿）: {}", e.getMessage());
        }
    }

    private void counter(String outcome) {
        registry.counter(PaymentConflictService.METRIC_COMPENSATE, "outcome", outcome).increment();
    }

    private void counter(String outcome, double amount) {
        registry.counter(PaymentConflictService.METRIC_COMPENSATE, "outcome", outcome).increment(amount);
    }
}
