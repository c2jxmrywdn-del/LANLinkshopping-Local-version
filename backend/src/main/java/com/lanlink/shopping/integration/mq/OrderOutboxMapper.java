package com.lanlink.shopping.integration.mq;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderOutboxMapper extends BaseMapper<OrderOutbox> {

    @Select("SELECT * FROM t_order_outbox WHERE status = 0 AND next_retry_at <= #{now} "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<OrderOutbox> claimDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE t_order_outbox SET status = 1, update_time = NOW() "
            + "WHERE message_key = #{messageKey} AND status = 0")
    int markSentByKey(@Param("messageKey") String messageKey);

    @Update("UPDATE t_order_outbox SET status = 1, update_time = NOW() WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") Long id);

    @Update("UPDATE t_order_outbox SET retry_count = #{retryCount}, next_retry_at = #{nextRetryAt}, "
            + "update_time = NOW() WHERE id = #{id} AND status = 0")
    int markRetry(@Param("id") Long id, @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Update("UPDATE t_order_outbox SET status = 2, update_time = NOW() WHERE id = #{id} AND status = 0")
    int markDead(@Param("id") Long id);
}
