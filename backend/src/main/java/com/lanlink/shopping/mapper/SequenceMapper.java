package com.lanlink.shopping.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 业务单号序列表访问（t_sys_sequence）。
 * 单号生成统一走数据库原子自增，替代 count+1 取号在并发下的竞态。
 */
public interface SequenceMapper {

    /** 幂等初始化序列行（已存在则不动作） */
    @Insert("INSERT IGNORE INTO t_sys_sequence (seq_key, current_val) VALUES (#{seqKey}, 0)")
    int insertIgnore(@Param("seqKey") String seqKey);

    /**
     * 原子自增并把新值写入连接级 LAST_INSERT_ID()，
     * 同连接内紧接着 SELECT LAST_INSERT_ID() 即可无锁取到唯一递增值。
     */
    @Update("UPDATE t_sys_sequence SET current_val = LAST_INSERT_ID(current_val + #{step}) " +
            "WHERE seq_key = #{seqKey}")
    int increment(@Param("seqKey") String seqKey, @Param("step") long step);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();
}
