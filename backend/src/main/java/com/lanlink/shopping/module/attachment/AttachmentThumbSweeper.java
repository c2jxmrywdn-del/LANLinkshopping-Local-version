package com.lanlink.shopping.module.attachment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import com.lanlink.shopping.module.attachment.service.AttachmentService;
import com.lanlink.shopping.module.attachment.service.AttachmentThumbStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 缩略图补投扫描：把没进队列、或处理到一半进程挂掉的行重新推给 MQ。
 *
 * <p>没有这一层，"入队失败只是不阻断上传"就退化成"入队失败就永远没有预览"。这里不新建 outbox 表：
 * t_attachment 自身已经有 thumb_status 与 thumb_attempts，行状态就是待办清单，再复制一份反而会出现
 * 两份真值。认领与次数上限由消费端的 CAS 负责，本类只负责再投一次。
 */
@Component
@ConditionalOnProperty(name = "app.mq.attachment-flow", havingValue = "mq", matchIfMissing = true)
public class AttachmentThumbSweeper {

    private static final Logger log = LoggerFactory.getLogger(AttachmentThumbSweeper.class);
    private static final int BATCH_SIZE = 50;
    /** 处理中但这么久没回写，认定消费者已崩在半路，可以重新排队（正常一张图远快于此） */
    static final int STALE_PROCESSING_MINUTES = 10;

    private final AttachmentMapper attachments;
    private final AttachmentImagePublisher publisher;

    public AttachmentThumbSweeper(AttachmentMapper attachments, AttachmentImagePublisher publisher) {
        this.attachments = attachments;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${app.mq.attachment-sweep-interval-ms:120000}",
            initialDelayString = "${app.mq.attachment-sweep-initial-delay-ms:45000}")
    public void scheduledSweep() {
        int republished = sweep(LocalDateTime.now());
        if (republished > 0) {
            log.info("[attachment-mq] 补投缩略图任务 {} 条", republished);
        }
    }

    int sweep(LocalDateTime now) {
        List<Attachment> due = attachments.selectList(Wrappers.<Attachment>lambdaQuery()
                .ne(Attachment::getStatus, AttachmentService.STATUS_EXPIRED)
                .lt(Attachment::getThumbAttempts, AttachmentThumbStatus.MAX_ATTEMPTS)
                .and(w -> w.in(Attachment::getThumbStatus,
                            AttachmentThumbStatus.PENDING, AttachmentThumbStatus.FAILED)
                        .or(x -> x.eq(Attachment::getThumbStatus, AttachmentThumbStatus.PROCESSING)
                                .lt(Attachment::getUpdateTime, now.minusMinutes(STALE_PROCESSING_MINUTES))))
                .orderByAsc(Attachment::getAttachmentId)
                .last("LIMIT " + BATCH_SIZE));
        for (Attachment row : due) {
            publisher.publishQueued(AttachmentUploaded.of(row));
        }
        return due.size();
    }
}
