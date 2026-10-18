package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LogisticsSettlement;
import com.lanlink.shopping.entity.LogisticsSettlementItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface LogisticsSettlementMapper extends BaseMapper<LogisticsSettlement> {

    /** 同服务商同账期非作废结算单数（幂等判断用） */
    @Select("SELECT COUNT(*) FROM t_logistics_settlement WHERE provider_id = #{providerId} AND period_start = #{periodStart} AND period_end = #{periodEnd} AND deleted = 0")
    long countByProviderPeriod(@Param("providerId") Long providerId,
                               @Param("periodStart") LocalDate periodStart,
                               @Param("periodEnd") LocalDate periodEnd);
}
