package com.lanlink.shopping.payment.conflict.service;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.payment.conflict.entity.PaymentConflict;
import com.lanlink.shopping.payment.conflict.mapper.PaymentConflictMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 支付收款冲突挂账与状态机（P0-B）。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>挂账落库</b>：{@link #record} 把"渠道已扣款、本地订单已关闭"的事实写入
 *       {@code t_payment_conflict}，并做渠道流水的幂等归一化；</li>
 *   <li><b>状态机</b>：OPEN → AUTO_REFUNDING → REFUNDED / FAILED → MANUAL，
 *       所有流转走 Mapper 的 SQL 条件更新（CAS），自动补偿与人工处置并发时只有一方成功；</li>
 *   <li><b>退款网关</b>：真正的渠道退款调用通过 {@link ConflictRefundGateway} 注入。
 *       本轮（骨架）不接线，网关缺失时补偿 job 整体跳过；下一轮由 PaymentService 实现
 *       {@code refundForConflict} 后注入适配器。</li>
 * </ul>
 *
 * <p>为什么补偿要拆成"抢单 / 调渠道 / 收尾"三段：渠道 HTTP 调用不能包在数据库事务里
 * （长时间占用连接与行锁）；三段各自 {@code REQUIRES_NEW} 独立事务，由
 * {@code PaymentConflictCompensator} 在事务外串起来。
 */
@Service
public class PaymentConflictService {

    private static final Logger log = LoggerFactory.getLogger(PaymentConflictService.class);

    /** 关单/取消后收到渠道付款（主场景） */
    public static final String TYPE_LATE_PAY_AFTER_CLOSE = "late_pay_after_close";
    /** 同一订单出现第二笔渠道流水（重复扣款） */
    public static final String TYPE_DUPLICATE_TXN = "duplicate_txn";
    /** 守卫生效但状态既非已支付也非可结算（防御性，正常不出现） */
    public static final String TYPE_DATA_INVARIANT_UNKNOWN = "data_invariant_unknown";

    /** 渠道流水号缺失时的归一化前缀：唯一键要求 transaction_id NOT NULL */
    static final String TXN_FALLBACK_PREFIX = "NA:";

    /** 状态常量（与 DDL 注释一致） */
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_AUTO_REFUNDING = "AUTO_REFUNDING";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_MANUAL = "MANUAL";

    /** 指标名（与 OrderTimeoutCloser 的 lanlink.order.autoclose{outcome} 命名风格对齐） */
    public static final String METRIC_DETECTED = "lanlink.payment.conflict";
    public static final String METRIC_COMPENSATE = "lanlink.payment.conflict.compensate";
    public static final String METRIC_MANUAL = "lanlink.payment.conflict.manual";

    /** 重试退避（秒）：1m → 5m → 15m → 1h → 6h → 24h，之后固定 24h */
    private static final long[] BACKOFF_SECONDS = {60L, 300L, 900L, 3600L, 21600L, 86400L};

    private static final DateTimeFormatter OUT_REFUND_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PaymentConflictMapper mapper;
    private final MeterRegistry registry;

    /** 渠道退款网关（本轮骨架不接线；缺失时补偿 job 跳过，避免空转烧重试次数） */
    private ConflictRefundGateway refundGateway;

    @Value("${app.payment.conflict-max-retry:8}")
    private int maxRetry = 8;

    public PaymentConflictService(PaymentConflictMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
        // conflict.open Gauge：让「挂账积压」告警读真实未闭环数，而不是 detected−refunded−manual 的代理差值。
        // mapper 为单例代理，Gauge 在抓取时再查库，不会在注册时产生查询。
        io.micrometer.core.instrument.Gauge
                .builder("lanlink.payment.conflict.open", mapper,
                        (PaymentConflictMapper m) -> (double) m.countOpen())
                .description("未闭环的支付冲突挂账数（OPEN/FAILED/AUTO_REFUNDING）")
                .register(registry);
    }

    @Autowired(required = false)
    public void setRefundGateway(ConflictRefundGateway refundGateway) {
        this.refundGateway = refundGateway;
    }

    // ===== 值对象/扩展点 =====

    /** 渠道退款结果（内部值对象，与 PaymentService.refund 的返回值解耦） */
    public record RefundAttempt(boolean ok, String refundId, String message) {
        public static RefundAttempt success(String refundId) {
            return new RefundAttempt(true, refundId, null);
        }

        public static RefundAttempt failure(String message) {
            return new RefundAttempt(false, null, message);
        }
    }

    /**
     * 渠道退款网关：下一轮由 PaymentService 的"只退渠道、不动订单"方法实现并注册为 Bean。
     * 返回 {@link RefundAttempt}；抛异常视为一次失败（由 {@link #attemptRefund} 兜底为 failure）。
     */
    @FunctionalInterface
    public interface ConflictRefundGateway {
        RefundAttempt refund(PaymentConflict conflict);
    }

    // ===== 挂账落库 =====

    /**
     * 挂账落库（幂等）。
     *
     * <p>必须独立事务（{@code REQUIRES_NEW}）：调用方在渠道回调线程里，
     * 挂账提交成功之后才允许向渠道 ACK；写不进挂账时调用方应返回 FAIL 让渠道重试，
     * 绝不允许"挂账失败仍 ACK"。
     *
     * @return true=本次新挂账；false=该笔渠道流水已挂过账（重复回调/重试），不重复触发补偿
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(Order order, String channel, String transactionId,
                          BigDecimal amount, String conflictType, String source) {
        if (order == null || !hasText(order.getOrderNo())) {
            throw new IllegalArgumentException("挂账必须携带订单信息");
        }
        PaymentConflict c = new PaymentConflict();
        c.setOrderNo(order.getOrderNo());
        c.setConflictType(hasText(conflictType) ? conflictType : TYPE_LATE_PAY_AFTER_CLOSE);
        c.setOrderStatusAtDetect(order.getOrderStatus());
        c.setPayStatusAtDetect(order.getPayStatus());
        c.setChannel(normalizeChannel(channel));
        c.setTransactionId(normalizeTransactionId(order.getOrderNo(), transactionId));
        c.setAmount(amount == null ? BigDecimal.ZERO : amount);
        c.setPayerUserId(order.getUserId());
        c.setDetectedAt(LocalDateTime.now());
        c.setStatus(STATUS_OPEN);

        int affected = mapper.insertIgnore(c);
        if (affected > 0) {
            registry.counter(METRIC_DETECTED, "outcome", "detected").increment();
            log.warn("[PAYMENT] 收款冲突挂账 orderNo={} channel={} txn={} amount={} type={} source={}",
                    c.getOrderNo(), c.getChannel(), c.getTransactionId(), c.getAmount(),
                    c.getConflictType(), source);
            return true;
        }
        // 唯一键命中：同一笔渠道流水已挂账，保持既有处置进度，不重复触发补偿
        registry.counter(METRIC_DETECTED, "outcome", "duplicate").increment();
        log.info("[PAYMENT] 收款冲突重复上报，忽略 orderNo={} channel={} txn={} source={}",
                c.getOrderNo(), c.getChannel(), c.getTransactionId(), source);
        return false;
    }

    /**
     * 渠道流水号归一化：空白一律落 {@code NA:<orderNo>}。
     * 这是唯一键 {@code (channel, transaction_id)} 生效的前提——MySQL 唯一键不约束 NULL，
     * 若把查单场景的 null 直接落库，同一笔款可被重复挂账并重复退款。
     */
    public static String normalizeTransactionId(String orderNo, String transactionId) {
        return hasText(transactionId) ? transactionId.trim() : TXN_FALLBACK_PREFIX + orderNo;
    }

    /** 渠道归一化：空白落 unknown（channel 列 NOT NULL） */
    public static String normalizeChannel(String channel) {
        return hasText(channel) ? channel.trim().toLowerCase() : "unknown";
    }

    /** 第 retry 次失败后的退避秒数（retry 从 1 开始；超过 6 次固定 24h） */
    public static long backoffSeconds(int retry) {
        if (retry <= 0) {
            return BACKOFF_SECONDS[0];
        }
        int idx = Math.min(retry, BACKOFF_SECONDS.length) - 1;
        return BACKOFF_SECONDS[idx];
    }

    // ===== 自动补偿状态机 =====

    /** 网关是否已接线（未接线时补偿 job 跳过，不消耗重试次数） */
    public boolean canAutoRefund() {
        return refundGateway != null;
    }

    /**
     * 补偿抢单（独立事务）：OPEN/FAILED → AUTO_REFUNDING。
     *
     * @return 抢单成功的挂账快照（含落库的 out_refund_no）；null=已被其它执行方抢走/状态不允许
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentConflict claimForCompensation(Long conflictId, LocalDateTime now) {
        if (conflictId == null) {
            return null;
        }
        PaymentConflict c = mapper.selectById(conflictId);
        if (c == null || !isPending(c.getStatus())) {
            return null;
        }
        String outRefundNo = hasText(c.getOutRefundNo()) ? c.getOutRefundNo() : newOutRefundNo();
        if (mapper.claimForRefund(conflictId, outRefundNo) == 0) {
            return null;
        }
        // 重新读取：以库内落定的 out_refund_no/retry_count 为准（渠道幂等单号必须与库一致）
        PaymentConflict claimed = mapper.selectById(conflictId);
        if (claimed == null) {
            log.warn("[PAYMENT] 补偿抢单后读不到挂账 conflictId={}", conflictId);
            return null;
        }
        return claimed;
    }

    /** 调渠道退款（无事务；异常一律收敛为 failure，交给状态机记失败） */
    public RefundAttempt attemptRefund(PaymentConflict conflict) {
        if (conflict == null) {
            return RefundAttempt.failure("conflict required");
        }
        if (refundGateway == null) {
            return RefundAttempt.failure("refund gateway not wired");
        }
        try {
            RefundAttempt attempt = refundGateway.refund(conflict);
            return attempt != null ? attempt : RefundAttempt.failure("refund gateway returned null");
        } catch (Exception e) {
            return RefundAttempt.failure(truncate(e.getMessage(), 500));
        }
    }

    /** 退款成功收尾（独立事务）：AUTO_REFUNDING → REFUNDED */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finishRefunded(Long conflictId, String refundId) {
        int affected = mapper.markRefunded(conflictId, refundId);
        if (affected > 0) {
            registry.counter(METRIC_COMPENSATE, "outcome", "refunded").increment();
            log.info("[PAYMENT] 收款冲突自动原路退款成功 conflictId={} refundId={}", conflictId, refundId);
            return true;
        }
        registry.counter(METRIC_COMPENSATE, "outcome", "stale").increment();
        return false;
    }

    /**
     * 退款失败收尾（独立事务）：AUTO_REFUNDING → FAILED（退避）或 MANUAL（达上限转人工）。
     *
     * @return true=已转人工（需运营处置）；false=还会自动重试
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finishFailed(Long conflictId, String error, LocalDateTime now) {
        PaymentConflict c = mapper.selectById(conflictId);
        if (c == null) {
            registry.counter(METRIC_COMPENSATE, "outcome", "stale").increment();
            return false;
        }
        int currentRetry = c.getRetryCount() == null ? 0 : c.getRetryCount();
        int newRetry = currentRetry + 1;
        boolean toManual = newRetry >= maxRetry;
        String toStatus = toManual ? STATUS_MANUAL : STATUS_FAILED;
        LocalDateTime nextRetryAt = toManual ? null : now.plusSeconds(backoffSeconds(newRetry));

        int affected = mapper.bumpRetry(conflictId, toStatus, truncate(error, 500), nextRetryAt);
        if (affected > 0) {
            registry.counter(METRIC_COMPENSATE, "outcome", toManual ? "to_manual" : "failed").increment();
        } else {
            registry.counter(METRIC_COMPENSATE, "outcome", "stale").increment();
        }
        if (toManual) {
            log.error("[PAYMENT] 收款冲突自动退款失败达上限，转人工 conflictId={} orderNo={} retry={} error={}",
                    conflictId, c.getOrderNo(), newRetry, error);
        } else {
            log.warn("[PAYMENT] 收款冲突自动退款失败，{} 秒后重试 conflictId={} retry={} error={}",
                    backoffSeconds(newRetry), conflictId, newRetry, error);
        }
        return toManual;
    }

    // ===== 人工处置（运营后台；状态机 CAS 防与自动补偿并发双退） =====

    /** 人工抢单：OPEN/FAILED → MANUAL */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimManual(Long conflictId, Long adminId) {
        boolean ok = mapper.claimManual(conflictId, adminId) > 0;
        if (ok) {
            registry.counter(METRIC_MANUAL, "outcome", "claimed").increment();
        }
        return ok;
    }

    /** 人工登记已退款：MANUAL → MANUAL_REFUNDED */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markManualRefunded(Long conflictId, String refundId, String remark) {
        boolean ok = mapper.markManualRefunded(conflictId, refundId, remark) > 0;
        if (ok) {
            registry.counter(METRIC_MANUAL, "outcome", "refunded").increment();
        }
        return ok;
    }

    /** 人工核销：MANUAL → MANUAL_CLOSED */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markManualClosed(Long conflictId, String remark) {
        boolean ok = mapper.markManualClosed(conflictId, remark) > 0;
        if (ok) {
            registry.counter(METRIC_MANUAL, "outcome", "closed").increment();
        }
        return ok;
    }

    // ===== 工具 =====

    private static boolean isPending(String status) {
        return STATUS_OPEN.equals(status) || STATUS_FAILED.equals(status);
    }

    private static String newOutRefundNo() {
        return "CR" + LocalDateTime.now().format(OUT_REFUND_NO_FMT)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
