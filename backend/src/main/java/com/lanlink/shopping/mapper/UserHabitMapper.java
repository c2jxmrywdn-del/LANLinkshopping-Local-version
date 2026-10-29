package com.lanlink.shopping.mapper;

import org.apache.ibatis.annotations.*;
import java.util.Map;

/** 用户习惯画像（分析类 Cookie 收集的服务端同步副本，跨设备续用与清除）。 */
@Mapper
public interface UserHabitMapper {

    @Select("SELECT payload FROM t_user_habit WHERE user_id=#{userId}")
    String findPayload(@Param("userId") Long userId);

    /** 推荐个性化数据源：仅 analytics 已授权（consent=1）的画像 */
    @Select("SELECT payload FROM t_user_habit WHERE user_id=#{userId} AND consent=1")
    String findPayloadConsented(@Param("userId") Long userId);

    @Insert("INSERT INTO t_user_habit(user_id, payload, consent, consent_at, updated_at) " +
            "VALUES(#{userId}, #{payload}, 1, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE payload=VALUES(payload), consent=1, consent_at=NOW(), updated_at=NOW()")
    int upsert(@Param("userId") Long userId, @Param("payload") String payload);

    @Delete("DELETE FROM t_user_habit WHERE user_id=#{userId}")
    int deleteByUser(@Param("userId") Long userId);
}
