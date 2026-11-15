package com.lanlink.shopping.payment.conflict.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.payment.conflict.entity.PaymentConflict;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付收款冲突挂账 Mapper（P0-B）。
 *
 * <p>并发安全全部落在 SQL 条件更新上（不引入分布式锁/Redis）：
 * 自动补偿与人工处置靠 {@code status} 的 CAS 抢单，同一笔挂账同一时刻只有一个执行方能成功；
 * 唯一键 {@code uk_conflict_channel_txn(channel, transaction_id)} 兜底防重复挂账。
 *
 * <p>注意：{@code insert} / {@code selectById} 由 {@link BaseMapper} 提供（表映射齐全），
 * 本接口只补充幂等插入与状态机条件更新；不要重复声明 {@code insert}，
 * 否则 MyBatis-Plus 注入的 statement 与注解 statement 会冲突。
 */
public interface PaymentConflictMapper extends BaseMapper<PaymentConflict> {

    /** 插入列（insertIgnore 复用；不含自增主键与 create_time/update_time，由 SQL NOW() 兜底） */
    String INSERT_SQL = "INSERT INTO t_payment_conflict(order_no, conflict_type, order_status_at_detect,"
            + " pay_status_at_detect, channel, transaction_id, amount, payer_user_id, detected_at, status)"
            + " VALUES(#{orderNo}, #{conflictType}, #{orderStatusAtDetect}, #{payStatusAtDetect},"
            + " #{channel}, #{transactionId}, #{amount}, #{payerUserId}, #{detectedAt}, #{status})";

    /**
     * 幂等挂账：命中唯一键 {@code (channel, transaction_id)} 时不报错、不覆盖已有处置进度。
     * 返回 0 表示"该渠道流水已挂过账"，调用方据此不重复触发补偿。
     */
    @Insert(INSERT_SQL + " ON DUPLICATE KEY UPDATE update_time = update_time")
    int insertIgnore(PaymentConflict conflict);

    /** 按渠道流水反查挂账（唯一键维度；测试与人工排查用） */
    @Select("SELECT * FROM t_payment_conflict WHERE channel = #{channel} AND transaction_id = #{transactionId}")
    PaymentConflict selectByChannelTxn(@Param("channel") String channel,
                                       @Param("transactionId") String transactionId);

    /**
     * 未闭环挂账数：供 {@code lanlink.payment.conflict.open} Gauge 上报。
     *
     * <p>计入 OPEN / FAILED / AUTO_REFUNDING（退款在途）三种「还没结清」的状态；
     * 不含 REFUNDED / MANUAL_* 终态。积压告警直接读这个真实值，不再用 detected−refunded−manual 的代理差值。
     */
    @Select("SELECT COUNT(*) FROM t_payment_conflict "
            + "WHERE status IN ('OPEN', 'FAILED', 'AUTO_REFUNDING')")
    long countOpen();

    /**
     * 补偿扫描：待补偿（OPEN/FAILED）、已到重试时间、且未超出扫描窗口（只处理最近挂账，历史交人工）。
     * LIMIT 由调用方给定，限制单轮持有的行数与长事务风险。
     */
    @Select("SELECT * FROM t_payment_conflict WHERE status IN ('OPEN', 'FAILED')"
            + " AND (next_retry_at IS NULL OR next_retry_at <= #{now})"
            + " AND detected_at >= #{since}"
            + " ORDER BY detected_at ASC LIMIT #{limit}")
    List<PaymentConflict> selectOpenBatch(@Param("now") LocalDateTime now,
                                          @Param("since") LocalDateTime since,
                                          @Param("limit") int limit);

    /**
     * 自动补偿抢单：OPEN/FAILED → AUTO_REFUNDING，并保证 {@code out_refund_no} 只写一次
     * （渠道退款幂等键；多实例/事件与 job 并发时只有一个执行方拿到 affected=1）。
     */
    @Update("UPDATE t_payment_conflict SET status = 'AUTO_REFUNDING',"
            + " out_refund_no = COALESCE(out_refund_no, #{outRefundNo}), update_time = NOW()"
            + " WHERE conflict_id = #{id} AND status IN ('OPEN', 'FAILED')")
    int claimForRefund(@Param("id") Long id, @Param("outRefundNo") String outRefundNo);

    /** 自动退款成功：AUTO_REFUNDING → REFUNDED（只允许抢单成功的一方推进） */
    @Update("UPDATE t_payment_conflict SET status = 'REFUNDED', resolution = 'AUTO_REFUND',"
            + " refund_id = #{refundId}, resolved_at = NOW(), last_error = NULL, update_time = NOW()"
            + " WHERE conflict_id = #{id} AND status = 'AUTO_REFUNDING'")
    int markRefunded(@Param("id") Long id, @Param("refundId") String refundId);

    /**
     * 自动退款失败：AUTO_REFUNDING → FAILED（退避重试）或 MANUAL（达上限转人工）。
     * retry_count 在同一语句内自增，避免"读-改-写"竞态。
     */
    @Update("UPDATE t_payment_conflict SET status = #{toStatus}, retry_count = retry_count + 1,"
            + " last_error = #{lastError}, next_retry_at = #{nextRetryAt}, update_time = NOW()"
            + " WHERE conflict_id = #{id} AND status = 'AUTO_REFUNDING'")
    int bumpRetry(@Param("id") Long id, @Param("toStatus") String toStatus,
                  @Param("lastError") String lastError, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * 僵死抢单回收：进程在"抢单成功后、调渠道前"崩溃会把行永久留在 AUTO_REFUNDING，
     * 导致它既不会被扫描也不会被重试；超过 N 分钟未更新则放回 FAILED 立即可重试。
     */
    @Update("UPDATE t_payment_conflict SET status = 'FAILED', next_retry_at = NOW(),"
            + " last_error = 'stale AUTO_REFUNDING recovered', update_time = NOW()"
            + " WHERE status = 'AUTO_REFUNDING' AND update_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    int recoverStaleClaims(@Param("minutes") int minutes);

    /** 人工抢单：OPEN/FAILED → MANUAL（阻止自动补偿继续处理，人工未完成前不被 job 抢走） */
    @Update("UPDATE t_payment_conflict SET status = 'MANUAL', resolved_by = #{resolvedBy}, update_time = NOW()"
            + " WHERE conflict_id = #{id} AND status IN ('OPEN', 'FAILED')")
    int claimManual(@Param("id") Long id, @Param("resolvedBy") Long resolvedBy);

    /** 人工登记已退款：MANUAL → MANUAL_REFUNDED */
    @Update("UPDATE t_payment_conflict SET status = 'MANUAL_REFUNDED', resolution = 'MANUAL_REFUND',"
            + " refund_id = #{refundId}, remark = #{remark}, resolved_at = NOW(), update_time = NOW()"
            + " WHERE conflict_id = #{id} AND status = 'MANUAL'")
    int markManualRefunded(@Param("id") Long id, @Param("refundId") String refundId,
                           @Param("remark") String remark);

    /** 人工核销：MANUAL → MANUAL_CLOSED（渠道确认未扣款/已线下处理） */
    @Update("UPDATE t_payment_conflict SET status = 'MANUAL_CLOSED', resolution = 'MANUAL_WRITE_OFF',"
            + " remark = #{remark}, resolved_at = NOW(), update_time = NOW()"
            + " WHERE conflict_id = #{id} AND status = 'MANUAL'")
    int markManualClosed(@Param("id") Long id, @Param("remark") String remark);
}
