package com.lanlink.shopping.module.membership.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 会员卡（营销中台·会员系统）
 * user_id 唯一：每个用户一张卡。growth 成长值 / points 积分。
 */
@Data
@TableName("t_member_card")
public class MemberCard {
    @TableId(type = IdType.AUTO)
    private Long cardId;
    private Long userId;
    private Long levelId;
    private Integer growth;
    private Integer points;
    private LocalDateTime updateTime;
}
