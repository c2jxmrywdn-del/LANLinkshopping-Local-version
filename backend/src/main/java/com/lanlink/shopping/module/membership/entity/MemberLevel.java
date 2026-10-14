package com.lanlink.shopping.module.membership.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 会员等级定义（营销中台·会员系统）
 * discount_rate: 等级折扣率（0.95 = 95 折）
 */
@Data
@TableName("t_member_level")
public class MemberLevel {
    @TableId(type = IdType.AUTO)
    private Long levelId;
    private String levelName;
    private Integer minGrowth;
    private BigDecimal discountRate;
    private String description;
    private Integer sort;
}
