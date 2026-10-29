package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家↔商家 聊天消息（sender_role: buyer/merchant；read_flag 为对方是否已读）。
 * msg_type: text/image/file/voice；媒体消息 content 存占位文本（[图片]/[文件] 名/[语音]），
 * 媒体明细见 media_url/media_name/media_size/voice_seconds（MyBatis-Plus 驼峰映射 msg_type 等列）。
 */
@Data
@TableName("t_merchant_chat_msg")
public class MerchantChatMsg {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String senderRole;
    private Long senderId;
    private String content;
    /** 消息类型：text/image/file/voice（与库列 varchar(16) 对齐，直出 JSON，不引入枚举转换器） */
    private String msgType;
    private String mediaUrl;
    private String mediaName;
    private Long mediaSize;
    /** 语音时长（秒，仅 msgType=voice 时有值，1-300） */
    private Integer voiceSeconds;
    private Integer readFlag;
    private LocalDateTime createTime;
}
