package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 直播间互动弹幕与事件消息。
 * msgType: 'chat'(用户发言) | 'buy'(下单抢购) | 'system'(系统公告) | 'gift'(点赞礼物)
 */
@Data
@TableName("t_live_comment")
public class LiveComment {
    @TableId(type = IdType.AUTO)
    private Long commentId;
    private Long roomId;
    private Long userId;
    private String nickname;
    private String avatar;
    private String content;
    private String msgType;
    private LocalDateTime createTime;
}
