package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家↔商家 会话（按 buyer_id+mer_id 唯一，一买家对一商家一个会话）。
 * prod_id 记录最近咨询商品；unread_count 为"对方"未读数（结合 last_sender_role 判定归属）。
 */
@Data
@TableName("t_merchant_chat")
public class MerchantChat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long buyerId;
    private Long merId;
    private Long prodId;
    private String lastMsgContent;
    private String lastSenderRole;
    private Integer unreadCount;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
