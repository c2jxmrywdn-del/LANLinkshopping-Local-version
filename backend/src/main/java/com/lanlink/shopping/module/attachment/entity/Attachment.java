package com.lanlink.shopping.module.attachment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long attachmentId;
    private String attachNo;
    /** 业务域线名，取值见 AttachmentBizType.wireName() */
    private String bizType;
    /** 绑定的业务主键；PENDING 期为空 */
    private Long bizId;
    private Long ownerUserId;
    /** 仅展示用的净化文件名，不参与存储路径 */
    private String fileName;
    /** 对象存储键，永不进入任何对外响应 */
    private String objectKey;
    private String contentType;
    private Long fileSize;
    private String sha256;
    /** PENDING | BOUND | EXPIRED */
    private String status;
    /** PRIVATE | PUBLIC */
    private String visibility;
    /** 缩略图派生状态：PENDING 待处理 / READY 已生成 / SKIPPED 非图片不生成 / FAILED 处理失败待重试 */
    private String thumbStatus;
    /** 缩略图对象键；仅在 thumbStatus=READY 时有值，永不进入对外响应 */
    private String thumbKey;
    /** 消费端已尝试次数，用于给重试设上界（毒图不会永远占着消费者） */
    private Integer thumbAttempts;
    /** 异步解码得到的原始像素尺寸；0 表示尚未测得 */
    private Integer width;
    private Integer height;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
