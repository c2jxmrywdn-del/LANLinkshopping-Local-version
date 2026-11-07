package com.lanlink.shopping.module.attachment;

import com.lanlink.shopping.module.attachment.entity.Attachment;

/**
 * attachment.process 消息载荷。
 *
 * <p>只带定位信息，不带任何"要不要处理"的判断：消费端会重读库里的 thumb_status 与 attempts，
 * 因为从入队到消费之间这道判断可能已经变了（材料被回收、被别的消费者抢先、重试次数已用完）。
 * 幂等靠那次 CAS 认领，不靠消息唯一键。
 */
public record AttachmentUploaded(String attachNo, String objectKey, String contentType, long fileSize) {

    public static AttachmentUploaded of(Attachment entity) {
        return new AttachmentUploaded(entity.getAttachNo(), entity.getObjectKey(),
                entity.getContentType(), entity.getFileSize() == null ? 0L : entity.getFileSize());
    }
}
