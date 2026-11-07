package com.lanlink.shopping.module.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import com.lanlink.shopping.module.attachment.service.AttachmentContents;
import com.lanlink.shopping.module.attachment.service.AttachmentKeys;
import com.lanlink.shopping.module.attachment.service.AttachmentThumbnails;
import com.lanlink.shopping.module.attachment.service.AttachmentThumbStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 缩略图消费：认领 → 回源字节 → 魔数复核 → 尺寸校验 → 生成 → 回写。
 *
 * <p>刻意不开事务：认领是一次 CAS UPDATE，回写是一次 CAS UPDATE，各自原子；把整段包进事务会让
 * 数据库连接横跨 MinIO 的读与写（几百毫秒级），削峰时并发消费者会直接把连接池占满。
 * 这点与通知链路相反——那边去重与发消息必须在同一事务里，否则重投会被自己的去重键吞掉。
 *
 * <p>错误分两类：内容问题（解码失败、像素超限、魔数与库内类型不符）判终态 SKIPPED，重试一万次
 * 也不会变好，只会反复占住消费者；基础设施问题（存储/数据库抛错）置 FAILED 后继续上抛，交给
 * 容器重试与死信，让人看得见。载荷解析不了的属于毒消息，显式 reject 且不重回队列。
 */
@Component
@ConditionalOnProperty(name = "app.mq.attachment-flow", havingValue = "mq", matchIfMissing = true)
public class AttachmentImageConsumer {

    private static final Logger log = LoggerFactory.getLogger(AttachmentImageConsumer.class);

    private final AttachmentMapper attachments;
    private final StorageService storage;
    private final ObjectMapper json;
    private final MeterRegistry registry;
    private final Timer duration;

    public AttachmentImageConsumer(AttachmentMapper attachments, StorageService storage,
                                   ObjectMapper json, MeterRegistry registry) {
        this.attachments = attachments;
        this.storage = storage;
        this.json = json;
        this.registry = registry;
        this.duration = Timer.builder("lanlink.attachment.thumbnail.duration")
                .description("单张缩略图消费耗时，用于 P95 与吞吐观测")
                .publishPercentileHistogram()
                .register(registry);
    }

    @RabbitListener(queues = AttachmentRabbitConfig.QUEUE,
            concurrency = "${app.mq.attachment-consumer-concurrency:1-3}")
    public void consume(String body) {
        AttachmentUploaded payload;
        try {
            payload = json.readValue(body, AttachmentUploaded.class);
        } catch (Exception e) {
            count("poison");
            throw new AmqpRejectAndDontRequeueException("附件处理载荷不可解析", e);
        }
        long started = System.nanoTime();
        try {
            if (!claim(payload.attachNo())) {
                count("duplicate");
                return;
            }
            process(payload);
        } finally {
            duration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** CAS 认领：只有把行从待处理改成处理中的那个消费者才继续，重投与并发消费者都被挡在这里。 */
    private boolean claim(String attachNo) {
        Attachment to = new Attachment();
        to.setThumbStatus(AttachmentThumbStatus.PROCESSING);
        int taken = attachments.update(to, Wrappers.<Attachment>lambdaUpdate()
                .eq(Attachment::getAttachNo, attachNo)
                .lt(Attachment::getThumbAttempts, AttachmentThumbStatus.MAX_ATTEMPTS)
                .in(Attachment::getThumbStatus, AttachmentThumbStatus.PENDING, AttachmentThumbStatus.FAILED)
                .setSql("thumb_attempts = thumb_attempts + 1"));
        return taken == 1;
    }

    private void process(AttachmentUploaded payload) {
        Attachment row = attachments.selectOne(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getAttachNo, payload.attachNo()));
        if (row == null) {
            // 行已被回收流程置 EXPIRED 并删除对象，或单号写错：无事可做，不算失败
            count("missing-row");
            return;
        }
        byte[] source;
        try {
            source = storage.getObject(row.getObjectKey());
        } catch (RuntimeException e) {
            markFailed(row.getAttachNo());
            count("infra");
            throw e;
        }

        String actualType;
        try {
            actualType = AttachmentContents.sniff(source).contentType();
        } catch (BusinessException e) {
            log.warn("[attachment-mq] 魔数复核不通过，不生成预览 no={}", row.getAttachNo());
            finish(row.getAttachNo(), AttachmentThumbStatus.SKIPPED, null, null, null);
            count("magic-mismatch");
            return;
        }
        if (!AttachmentThumbnails.supports(actualType)) {
            finish(row.getAttachNo(), AttachmentThumbStatus.SKIPPED, null, null, null);
            count("unsupported");
            return;
        }

        AttachmentThumbnails.Result thumb;
        try {
            thumb = AttachmentThumbnails.render(source);
        } catch (BusinessException | IOException e) {
            log.warn("[attachment-mq] 图片不可预览，判终态 no={} err={}", row.getAttachNo(), e.getMessage());
            finish(row.getAttachNo(), AttachmentThumbStatus.SKIPPED, null, null, null);
            count("undecodable");
            return;
        }

        String thumbKey = AttachmentKeys.thumbnailKey(row.getObjectKey());
        try {
            storage.put(thumbKey, thumb.content(), thumb.contentType());
        } catch (RuntimeException e) {
            markFailed(row.getAttachNo());
            count("infra");
            throw e;
        }
        finish(row.getAttachNo(), AttachmentThumbStatus.READY, thumbKey, thumb.width(), thumb.height());
        count("ready");
    }

    private void finish(String attachNo, String status, String thumbKey, Integer width, Integer height) {
        Attachment to = new Attachment();
        to.setThumbStatus(status);
        to.setThumbKey(thumbKey);
        to.setWidth(width);
        to.setHeight(height);
        attachments.update(to, Wrappers.<Attachment>lambdaUpdate()
                .eq(Attachment::getAttachNo, attachNo)
                .eq(Attachment::getThumbStatus, AttachmentThumbStatus.PROCESSING));
    }

    private void markFailed(String attachNo) {
        Attachment to = new Attachment();
        to.setThumbStatus(AttachmentThumbStatus.FAILED);
        attachments.update(to, Wrappers.<Attachment>lambdaUpdate()
                .eq(Attachment::getAttachNo, attachNo)
                .eq(Attachment::getThumbStatus, AttachmentThumbStatus.PROCESSING));
    }

    private void count(String outcome) {
        registry.counter("lanlink.attachment.thumbnail.processed", "outcome", outcome).increment();
    }
}
