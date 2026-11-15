package com.lanlink.shopping.module.merchantexit.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 退店读写 SQL。写语句全部把「前置状态」写进 WHERE，返回 0 表示状态不符或并发已被处理，
 * 由服务层翻译成 400 —— 不依赖先查后改，天然防并发跳步。
 */
@Mapper
public interface MerchantExitMapper {

    @Select("SELECT COUNT(*) FROM t_product WHERE mer_id = #{merId} AND deleted = 0 AND status = 1")
    long countOnSale(@Param("merId") Long merId);

    @Select("SELECT COUNT(*) FROM t_merchant_settlement WHERE mer_id = #{merId} AND status IN ('DRAFT','APPROVED')")
    long countOpenBatches(@Param("merId") Long merId);

    @Select("SELECT COUNT(*) FROM t_merchant_earning WHERE mer_id = #{merId} AND type = 'EARN' AND status = 'AVAILABLE'")
    long countAvailableEarnings(@Param("merId") Long merId);

    /** 时间闸门口径：该商户商品最近一次订单完成时刻；无已完成订单返回 NULL（闸门放行）。 */
    @Select("SELECT MAX(o.finish_time) FROM t_order o JOIN t_order_item oi ON oi.order_no = o.order_no"
            + " WHERE o.order_status = 2 AND oi.prod_id IN (SELECT prod_id FROM t_product WHERE mer_id = #{merId})")
    LocalDateTime lastFinishTime(@Param("merId") Long merId);

    @Update("UPDATE t_merchant SET exit_status = 'EXIT_REQUESTED', exit_request_time = NOW(),"
            + " exit_reason = #{reason}, exit_reject_reason = NULL, update_time = NOW()"
            + " WHERE mer_id = #{merId} AND deleted = 0 AND exit_status IS NULL")
    int requestExit(@Param("merId") Long merId, @Param("reason") String reason);

    @Update("UPDATE t_merchant SET exit_status = 'PUBLIC_NOTICE',"
            + " notice_end_time = DATE_ADD(NOW(), INTERVAL #{days} DAY), exit_reject_reason = NULL, update_time = NOW()"
            + " WHERE mer_id = #{merId} AND deleted = 0 AND exit_status = 'EXIT_REQUESTED'")
    int approve(@Param("merId") Long merId, @Param("days") int days);

    @Update("UPDATE t_merchant SET exit_status = NULL, exit_request_time = NULL, notice_end_time = NULL,"
            + " exit_reason = NULL, exit_reject_reason = #{reason}, update_time = NOW()"
            + " WHERE mer_id = #{merId} AND deleted = 0 AND exit_status = 'EXIT_REQUESTED'")
    int reject(@Param("merId") Long merId, @Param("reason") String reason);

    @Update("UPDATE t_merchant SET exit_status = NULL, exit_request_time = NULL, notice_end_time = NULL,"
            + " exit_reason = NULL, exit_reject_reason = NULL, update_time = NOW()"
            + " WHERE mer_id = #{merId} AND deleted = 0 AND exit_status = 'PUBLIC_NOTICE'")
    int withdraw(@Param("merId") Long merId);

    @Update("UPDATE t_merchant SET exit_status = 'FROZEN', update_time = NOW()"
            + " WHERE mer_id = #{merId} AND deleted = 0 AND exit_status = 'PUBLIC_NOTICE'"
            + " AND notice_end_time IS NOT NULL AND notice_end_time <= NOW()")
    int freezeDue(@Param("merId") Long merId);

    @Update("UPDATE t_merchant SET exit_status = 'CLOSED', close_time = NOW(), update_time = NOW()"
            + " WHERE mer_id = #{merId} AND deleted = 0 AND exit_status = 'FROZEN'")
    int close(@Param("merId") Long merId);
}
