package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 支付收款冲突挂账表初始化器（P0-B 关单/支付冲突处置）。
 *
 * <p>背景：DB 状态不变量成立（<code>OrderMapper.markPaidAtomic</code> 与
 * <code>closeUnpaidAtomic</code> 两条条件更新互斥），但外部资金事件可能晚于本地关单：
 * 渠道扣款成功、回调到达时订单已关闭/已取消——此时平台既不能"装作已支付"，也已回补库存/积分，
 * 唯一正确处置是把这笔钱记入本表并原路退回。表结构见
 * {@code reports/specs/p0b-payment-conflict.md §2.1}（22 列）。
 *
 * <p>幂等：先查 information_schema.TABLES，不存在才执行 DDL（仿
 * {@code RefundSchemaInitializer#createRmaTableIfNotExists} 的 try/catch warn 写法）；
 * 本类是 {@code t_payment_conflict} 的首个建表来源，schema.sql 由 Lead 下一轮统一合并，
 * 两处 DDL 必须逐字一致。
 *
 * <p>唯一键 <code>uk_conflict_channel_txn(channel, transaction_id)</code> 是防重复挂账/重复退款的
 * 最后一道闸：<b>transaction_id 必须 NOT NULL</b>（渠道查单拿不到流水号时，Java 侧统一归一为
 * {@code NA:<orderNo>}），否则 MySQL 唯一键对 NULL 不生效，同一笔款可被挂账多次。
 */
@Component
@Order(25)
public class PaymentConflictSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PaymentConflictSchemaInitializer.class);

    /** 表名常量：information_schema 守卫与 DDL 共用，避免拼写漂移 */
    static final String TABLE = "t_payment_conflict";

    /**
     * 22 列 DDL（与 reports/specs/p0b-payment-conflict.md §2.1 一致）。
     * 表注释写明语义，便于 DBA 对账时直接理解。
     */
    static final String DDL = "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
            "  conflict_id        BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '挂账主键ID'," +
            "  order_no           VARCHAR(40) NOT NULL COMMENT '平台订单号'," +
            "  conflict_type      VARCHAR(24) NOT NULL DEFAULT 'late_pay_after_close'" +
            "                     COMMENT 'late_pay_after_close关单后收款/duplicate_txn同单第二笔渠道流水/data_invariant_unknown未知态冲突'," +
            "  order_status_at_detect TINYINT COMMENT '挂账时订单状态快照(0待发货1已发货2已完成3已取消4部分发货5已退款)'," +
            "  pay_status_at_detect   TINYINT COMMENT '挂账时支付状态快照(0未支付1已支付2已退款)'," +
            "  channel            VARCHAR(16) NOT NULL COMMENT 'wechat/alipay/stripe/mock/wallet'," +
            "  transaction_id     VARCHAR(64) NOT NULL COMMENT '渠道交易号(缺失时归一为 NA:<orderNo>)'," +
            "  amount             DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '渠道实收金额(元)'," +
            "  payer_user_id      BIGINT DEFAULT NULL COMMENT '付款用户ID(取订单userId)'," +
            "  detected_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '冲突被发现/挂账时间'," +
            "  status             VARCHAR(16) NOT NULL DEFAULT 'OPEN'" +
            "                     COMMENT 'OPEN待补偿/AUTO_REFUNDING自动退款中/REFUNDED已自动原路退回/FAILED退款失败待重试/MANUAL人工处置中/MANUAL_REFUNDED人工已退/MANUAL_CLOSED人工核销'," +
            "  resolution         VARCHAR(32) DEFAULT NULL COMMENT '处置结论 AUTO_REFUND/MANUAL_REFUND/MANUAL_WRITE_OFF'," +
            "  out_refund_no      VARCHAR(64) DEFAULT NULL COMMENT '补偿退款请求单号(渠道幂等键)'," +
            "  refund_id          VARCHAR(64) DEFAULT NULL COMMENT '渠道退款流水号'," +
            "  retry_count        INT NOT NULL DEFAULT 0 COMMENT '自动退款重试次数'," +
            "  last_error         VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败原因(脱敏)'," +
            "  next_retry_at      DATETIME DEFAULT NULL COMMENT '下次重试时间(指数退避)'," +
            "  resolved_by        BIGINT DEFAULT NULL COMMENT '人工处置人ID'," +
            "  resolved_at        DATETIME DEFAULT NULL COMMENT '人工处置完成时间'," +
            "  remark             VARCHAR(500) DEFAULT NULL COMMENT '备注/人工处置说明'," +
            "  create_time        DATETIME DEFAULT CURRENT_TIMESTAMP," +
            "  update_time        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
            "  UNIQUE KEY uk_conflict_channel_txn (channel, transaction_id)," +
            "  KEY idx_conflict_status_retry (status, next_retry_at)," +
            "  KEY idx_conflict_order (order_no)," +
            "  KEY idx_conflict_detected (detected_at)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='支付收款冲突挂账(关单/取消后渠道扣款)'";

    private final JdbcTemplate jdbc;

    public PaymentConflictSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, TABLE);
            if (exists != null && exists > 0) {
                log.debug("[payment-conflict-schema] {} 已存在，跳过建表", TABLE);
                return;
            }
            jdbc.execute(DDL);
            log.info("[payment-conflict-schema] 已创建支付收款冲突挂账表 {}", TABLE);
        } catch (Exception e) {
            // 与 RefundSchemaInitializer 口径一致：单次失败不阻断启动（DDL 权限/并发建表等），下一轮或人工执行
            log.warn("[payment-conflict-schema] 初始化 {} 失败（可忽略，阻塞启动需排查）: {}", TABLE, e.getMessage());
        }
    }
}
