package com.lanlink.shopping.module.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.risk.entity.UserRiskEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserRiskEventMapper extends BaseMapper<UserRiskEvent> {

    /** 30 天窗口内同用户同规则是否已有未复核命中（检测器去重，防重复计分） */
    @Select("SELECT COUNT(*) FROM t_user_risk_event WHERE user_id=#{userId} AND rule_code=#{ruleCode} " +
            "AND reviewed_status IS NULL AND create_time>=DATE_SUB(NOW(),INTERVAL 24 HOUR)")
    long countRecentUnreviewed(@Param("userId") Long userId, @Param("ruleCode") String ruleCode);

    /** 规则 R01：7 天内取消率>60% 且取消>=3 单的用户（订单状态 3=已取消） */
    @Select("SELECT o.user_id userId, COUNT(*) cancels, ROUND(AVG(o.order_status=3),2) cancelRate " +
            "FROM t_order o WHERE o.create_time>=DATE_SUB(NOW(),INTERVAL 7 DAY) AND o.user_id IS NOT NULL " +
            "GROUP BY o.user_id HAVING COUNT(*)>=3 AND AVG(o.order_status=3)>0.6")
    List<Map<String, Object>> detectHighCancelUsers();

    /** 规则 R03：24h 内同商品下单>=5 次或数量>50 的用户 */
    @Select("SELECT o.user_id userId, oi.prod_id prodId, COUNT(*) times, SUM(oi.quantity) qty " +
            "FROM t_order_item oi JOIN t_order o ON oi.order_no=o.order_no " +
            "WHERE o.create_time>=DATE_SUB(NOW(),INTERVAL 24 HOUR) AND o.user_id IS NOT NULL " +
            "GROUP BY o.user_id, oi.prod_id HAVING COUNT(*)>=5 OR SUM(oi.quantity)>50")
    List<Map<String, Object>> detectBulkOrderUsers();

    /** 规则 R04：90 天内退款申请/成功>=3 次的用户（时间窗用订单创建时间；t_order 无 apply_time 列） */
    @Select("SELECT o.user_id userId, COUNT(*) refunds FROM t_order o " +
            "WHERE o.refund_status IN ('applied','success','partial') " +
            "AND o.create_time>=DATE_SUB(NOW(),INTERVAL 90 DAY) AND o.user_id IS NOT NULL " +
            "GROUP BY o.user_id HAVING COUNT(*)>=3")
    List<Map<String, Object>> detectRefundAbuseUsers();

    /** 规则 R06：同 IP 10 分钟登录失败>=8 次 / 同账号 24h 内>=4 个不同 IP（取涉及账号） */
    @Select("SELECT user_id userId, MAX(cnt) cnt FROM (" +
            "  SELECT user_id, client_ip, COUNT(*) cnt FROM t_login_log " +
            "  WHERE success=0 AND create_time>=DATE_SUB(NOW(),INTERVAL 10 MINUTE) AND user_id IS NOT NULL " +
            "  GROUP BY user_id, client_ip HAVING COUNT(*)>=8) t GROUP BY user_id")
    List<Map<String, Object>> detectLoginAnomalyUsers();

    /** 未复核事件按用户聚合（汇总任务输入） */
    @Select("SELECT user_id, SUM(score) total, COUNT(*) hits, MAX(create_time) last_time " +
            "FROM t_user_risk_event WHERE reviewed_status IS NULL OR reviewed_status=1 " +
            "GROUP BY user_id")
    List<Map<String, Object>> aggregateByUser();

    /** 汇总重建前清空（物化幂等） */
    @Delete("DELETE FROM t_user_risk_summary")
    int clearSummary();
}
