package com.lanlink.shopping.module.notify;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 业务事务内写箱（严格 Outbox）。
 *
 * <p>写箱必须与业务写库在同一事务里：业务回滚时不能留下一条会真的发出去的通知；
 * 业务提交成功时，即使进程在下一次指令前崩溃，箱里已经有这一行，worker 迟早会补投。
 * 这两条一起才构成"至少一次"，AFTER_COMMIT 直发只是把延迟降下来的快车道。
 */
class NotifyOutboxEnqueuerTest {

    private final NotifyOutboxMapper outbox = mock(NotifyOutboxMapper.class);
    private final NotifyOutboxEnqueuer enqueuer =
            new NotifyOutboxEnqueuer(outbox, new com.fasterxml.jackson.databind.ObjectMapper());

    private final TechSideEffectEvent event =
            new TechSideEffectEvent(1001L, "专家审核通过", "恭喜通过审核", "TE20261104001");

    @Test
    void enqueueWritesPendingRowCarryingTheFullPayload() {
        enqueuer.enqueue(event);

        ArgumentCaptor<NotifyOutbox> row = ArgumentCaptor.forClass(NotifyOutbox.class);
        verify(outbox).insert(row.capture());
        assertEquals(NotifyOutbox.STATUS_PENDING, row.getValue().getStatus());
        assertEquals(0, row.getValue().getRetryCount());
        assertTrue(row.getValue().getPayload().contains("恭喜通过审核"),
                "箱里要存完整载荷，worker 不需要回查业务表：" + row.getValue().getPayload());
        assertEquals(NotifyPayload.techKey(event), row.getValue().getMessageKey(),
                "去重键必须在写箱时就确定，快车道与重投共用同一个键");
    }

    @Test
    void pendingRowIsEligibleImmediatelySoFastLaneAndWorkerShareOnePath() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        enqueuer.enqueue(event);

        ArgumentCaptor<NotifyOutbox> row = ArgumentCaptor.forClass(NotifyOutbox.class);
        verify(outbox).insert(row.capture());
        assertNotNull(row.getValue().getNextRetryAt());
        assertTrue(row.getValue().getNextRetryAt().isAfter(before)
                        && !row.getValue().getNextRetryAt().isAfter(LocalDateTime.now().plusSeconds(2)),
                "新入箱行应立即可被领取，而不是被推到未来：" + row.getValue().getNextRetryAt());
    }

    @Test
    void duplicateEnqueueIsSwallowedBecauseSameEventReplayed() {
        doThrow(new DuplicateKeyException("dup")).when(outbox).insert(any(NotifyOutbox.class));

        // 业务重试会用同一个去重键再写一次：这是同一件事，不该报错也不该产生第二行
        enqueuer.enqueue(event);

        verify(outbox).insert(any(NotifyOutbox.class));
    }

    @Test
    void eventWithoutRecipientIsIgnoredWithoutTouchingTheBox() {
        enqueuer.enqueue(new TechSideEffectEvent(null, "t", "c", "n"));

        verify(outbox, never()).insert(any(NotifyOutbox.class));
    }
}
