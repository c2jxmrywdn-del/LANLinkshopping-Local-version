package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工客服工单：用户在「设置 → 人工客服」提交留言，运营在工作台处理并回复。
 * status：OPEN 待处理 / PROCESSING 处理中 / REPLIED 已回复 / CLOSED 已关闭。
 */
@Data
@TableName("t_support_ticket")
public class SupportTicket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String contact;
    private String category;
    private String title;
    private String content;
    private String status;
    private String reply;
    private Long replyBy;
    private LocalDateTime replyTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
