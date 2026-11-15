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

    /**
     * 认领待投递行（原子）。
     *
     * <p>原实现是普通 SELECT，多副本同时扫描会拿到同一批 id，各自
     * {@code convertAndSend} 一次——消费者虽然幂等，但 MQ 流量、消费日志、DLQ 噪声都会翻倍；
     * 一旦将来接入非幂等消费者就变成数据错误。
     *
     * <p>改用 {@code FOR UPDATE SKIP LOCKED}：同一行只能被一个事务锁住，
     * 另一个实例直接跳过（不阻塞等待），与同仓 {@code KbOutboxService.claimBatch} 口径一致。
     * 调用方必须把「认领 + 发送 + markSent」放在同一个事务里，否则锁在语句结束就释放了。
     */
    @Select("SELECT * FROM t_order_outbox WHERE status = 0 AND next_retry_at <= #{now} "
            + "ORDER BY id ASC LIMIT #{limit} FOR UPDATE SKIP LOCKED")
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
