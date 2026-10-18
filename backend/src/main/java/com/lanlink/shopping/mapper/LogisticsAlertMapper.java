package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LogisticsAlert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LogisticsAlertMapper extends BaseMapper<LogisticsAlert> {

    /** 同运单同类型未处理告警数（幂等判断用） */
    @Select("SELECT COUNT(*) FROM t_logistics_alert WHERE waybill_id = #{waybillId} AND alert_type = #{alertType} AND status <> 'resolved'")
    long countActiveByType(@Param("waybillId") Long waybillId, @Param("alertType") String alertType);
}
