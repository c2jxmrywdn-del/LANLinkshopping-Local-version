package com.lanlink.shopping.module.notify;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 通知消费去重记录：message_key 唯一，重复投递插入即冲突，据此跳过二次发信。 */
@TableName("t_notify_dedupe")
public class NotifyDedupe {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageKey;
    private LocalDateTime consumedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }
}
