package com.lanlink.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.entity.LogisticsTrace;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LogisticsTraceMapper extends BaseMapper<LogisticsTrace> {

    /** 最新一条轨迹（用于超时未更新检测） */
    @Select("SELECT * FROM t_logistics_trace WHERE waybill_id = #{waybillId} ORDER BY trace_time DESC LIMIT 1")
    LogisticsTrace selectLatest(@Param("waybillId") Long waybillId);
}
