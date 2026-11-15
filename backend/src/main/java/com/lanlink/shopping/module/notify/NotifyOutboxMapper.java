package com.lanlink.shopping.module.notify;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NotifyOutboxMapper extends BaseMapper<NotifyOutbox> {

    /**
     * 领取到期的待投行。状态条件写在 UPDATE 里做 CAS，避免多副本 worker 同时重投同一行；
     * 这里的 SELECT 只取候选，真正的互斥由 markSent/markRetry 的 status=0 条件保证。
     */
    @Select("SELECT * FROM t_notify_outbox WHERE status = 0 AND next_retry_at <= #{now} "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<NotifyOutbox> claimDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 认领单行：status 0 → 3(SENDING) 的条件更新。
     *
     * <p>多副本同时 SELECT 到同一批候选时，只有一方能把 status 从 0 改成 3，
     * 另一方返回 0 行并跳过，避免同一条通知被两个实例各投一次（用户收到重复消息）。
     */
    @Update("UPDATE t_notify_outbox SET status = 3, update_time = NOW() "
            + "WHERE id = #{id} AND status = 0")
    int claimOne(@Param("id") Long id);

    @Update("UPDATE t_notify_outbox SET status = 1, update_time = NOW() "
            + "WHERE id = #{id} AND status IN (0, 3)")
    int markSent(@Param("id") Long id);

    /** 快车道投递成功后按去重键结案；键由写箱与投递共用，不需要回查主键。 */
    @Update("UPDATE t_notify_outbox SET status = 1, update_time = NOW() "
            + "WHERE message_key = #{messageKey} AND status = 0")
    int markSentByKey(@Param("messageKey") String messageKey);

    @Update("UPDATE t_notify_outbox SET retry_count = #{retryCount}, next_retry_at = #{nextRetryAt}, "
            + "update_time = NOW(), status = 0 WHERE id = #{id} AND status IN (0, 3)")
    int markRetry(@Param("id") Long id, @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Update("UPDATE t_notify_outbox SET status = 2, update_time = NOW() WHERE id = #{id} AND status IN (0, 3)")
    int markDead(@Param("id") Long id);
}
