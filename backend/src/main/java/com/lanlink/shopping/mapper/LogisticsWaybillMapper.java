package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LogisticsWaybill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LogisticsWaybillMapper extends BaseMapper<LogisticsWaybill> {

    /**
     * 状态流转的条件更新（CAS）：只有库里的状态仍等于期望值时才写入。
     *
     * <p>为什么必须加：原实现是 {@code selectById → 内存状态机校验 → updateById}，
     * 而 {@code updateById} 的 SQL 只有 {@code WHERE waybill_id = ?}，没有任何状态条件。
     * 两个并发事务都读到「运输中」都能通过内存校验，后提交的一方无条件覆盖——
     * 已签收（DELIVERED 是终态）的运单会被一条迟到的轨迹回调改回「派送中」，
     * 而订单侧可能已是「已完成」，状态永久错乱且无自动修复路径。
     *
     * @return 1 = 抢到状态迁移；0 = 状态已被其他事务改过，调用方应放弃写入
     */
    @Update("UPDATE t_logistics_waybill SET status = #{target}, update_time = NOW()"
            + " WHERE waybill_id = #{waybillId} AND status = #{expected}")
    int transitStatusAtomic(@Param("waybillId") Long waybillId,
                            @Param("expected") String expected,
                            @Param("target") String target);

    /**
     * 带定位与签收时间的状态流转条件更新（语义同 {@link #transitStatusAtomic}）。
     */
    @Update("UPDATE t_logistics_waybill SET status = #{target},"
            + " current_lng = #{lng}, current_lat = #{lat}, current_location = #{location},"
            + " delivered_time = #{deliveredTime}, update_time = NOW()"
            + " WHERE waybill_id = #{waybillId} AND status = #{expected}")
    int transitStatusWithLocationAtomic(@Param("waybillId") Long waybillId,
                                        @Param("expected") String expected,
                                        @Param("target") String target,
                                        @Param("lng") java.math.BigDecimal lng,
                                        @Param("lat") java.math.BigDecimal lat,
                                        @Param("location") String location,
                                        @Param("deliveredTime") java.time.LocalDateTime deliveredTime);

    /**
     * e-POD 审核结论的 CAS：仅当 pod_audit_status 仍是 pending 时才写入。
     *
     * <p>同一份回执被两个人同时验签（或双击）时，第二个请求原本也会通过
     * 「读出来是 pending」的内存校验并整行覆盖，把第一个人已驳回的结论改成通过。
     * 这里用 pending 作为条件，只有第一个审核动作生效。
     */
    @Update("UPDATE t_logistics_waybill SET pod_audit_status = #{auditStatus},"
            + " pod_audit_note = #{note}, pod_audited_by = #{operatorId}, pod_audited_at = #{auditedAt},"
            + " status = #{status}, delivered_time = #{deliveredTime},"
            + " current_lng = #{lng}, current_lat = #{lat}, current_location = #{location},"
            + " update_time = NOW()"
            + " WHERE waybill_id = #{waybillId} AND pod_audit_status = 'pending'")
    int auditPodAtomic(@Param("waybillId") Long waybillId,
                       @Param("auditStatus") String auditStatus,
                       @Param("note") String note,
                       @Param("operatorId") Long operatorId,
                       @Param("auditedAt") java.time.LocalDateTime auditedAt,
                       @Param("status") String status,
                       @Param("deliveredTime") java.time.LocalDateTime deliveredTime,
                       @Param("lng") java.math.BigDecimal lng,
                       @Param("lat") java.math.BigDecimal lat,
                       @Param("location") String location);
}
