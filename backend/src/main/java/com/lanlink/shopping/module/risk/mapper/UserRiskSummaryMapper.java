package com.lanlink.shopping.module.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.risk.entity.UserRiskSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserRiskSummaryMapper extends BaseMapper<UserRiskSummary> {

    @Insert("INSERT INTO t_user_risk_summary(user_id, total_score, level, hit_count, last_event_time, updated_at) " +
            "VALUES(#{userId}, #{totalScore}, #{level}, #{hitCount}, #{lastEventTime}, NOW()) " +
            "ON DUPLICATE KEY UPDATE total_score=VALUES(total_score), level=VALUES(level), " +
            "hit_count=VALUES(hit_count), last_event_time=VALUES(last_event_time), updated_at=NOW()")
    int upsert(@Param("userId") Long userId, @Param("totalScore") int totalScore,
               @Param("level") String level, @Param("hitCount") int hitCount,
               @Param("lastEventTime") java.time.LocalDateTime lastEventTime);
}
