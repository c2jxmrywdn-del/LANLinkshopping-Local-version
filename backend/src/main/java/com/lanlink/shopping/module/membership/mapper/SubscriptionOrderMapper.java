package com.lanlink.shopping.module.membership.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.membership.entity.SubscriptionOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 订阅订单访问（t_subscription_order）。
 * 并发/幂等一律条件更新：支付状态机 pay_status 0→1→(2) 与生效闸 apply_status 0→1
 * 均由带状态条件的 UPDATE 单语句完成，返回 0 即被并发先行者抢占（回调重放安全）。
 */
public interface SubscriptionOrderMapper extends BaseMapper<SubscriptionOrder> {

    /**
     * 支付成功原子落账（状态机闸）：仅 pay_status=0 可翻转为 1；已取消(2)/已支付(1)单据拒绝结算。
     * 返回 0 = 单据不存在、非待支付（重复回调重放）或已被并发结算。
     * paid_at 由 JVM 传入（LocalDateTime.now()，+08）：MySQL 容器时钟为 UTC，SQL NOW() 会写错时区。
     */
    @Update("UPDATE t_subscription_order SET pay_status = 1, channel = #{channel}, "
            + "transaction_id = #{transactionId}, paid_at = #{paidAt} "
            + "WHERE order_no = #{orderNo} AND pay_status = 0")
    int markPaidAtomic(@Param("orderNo") String orderNo,
                       @Param("channel") String channel,
                       @Param("transactionId") String transactionId,
                       @Param("paidAt") java.time.LocalDateTime paidAt);

    /**
     * 订阅生效闸（状态机闸）：仅 apply_status=0 可翻转为 1，保证驱动 t_user_subscription
     * 生效/顺延的逻辑在并发回调/重复投递下只执行一次。
     */
    @Update("UPDATE t_subscription_order SET apply_status = 1 "
            + "WHERE order_no = #{orderNo} AND apply_status = 0")
    int markAppliedAtomic(@Param("orderNo") String orderNo);

    /** 自动续费扣款失败计次：仅待支付单累计（已结算/已取消不再计数） */
    @Update("UPDATE t_subscription_order SET retry_count = retry_count + 1 "
            + "WHERE order_no = #{orderNo} AND pay_status = 0")
    int incrementRetry(@Param("orderNo") String orderNo);

    /** 取消待支付订阅单（自动续费停止/失效清理）：仅 pay_status=0 可置为 2 */
    @Update("UPDATE t_subscription_order SET pay_status = 2 "
            + "WHERE order_no = #{orderNo} AND pay_status = 0")
    int markCancelledAtomic(@Param("orderNo") String orderNo);

    /** 撤销自动续费时同步取消该订阅名下全部待支付续费单 */
    @Update("UPDATE t_subscription_order SET pay_status = 2 "
            + "WHERE renew_sub_id = #{renewSubId} AND pay_status = 0")
    int cancelPendingByRenewSub(@Param("renewSubId") Long renewSubId);

    /** 发起支付后回写渠道选择（订阅单无 prepay_id 列，仅记渠道；幂等：同单重复发起覆盖） */
    @Update("UPDATE t_subscription_order SET channel = #{channel} WHERE order_no = #{orderNo}")
    int markChannel(@Param("orderNo") String orderNo, @Param("channel") String channel);
}
