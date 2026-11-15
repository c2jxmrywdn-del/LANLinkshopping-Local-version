package com.lanlink.shopping.module.merchantexit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 退店流转轨迹（审计侧留痕，只 INSERT）。 */
@Data
@TableName("t_merchant_exit_log")
public class MerchantExitLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long merId;
    private String fromStatus;
    private String toStatus;
    private Long actorId;
    /** MERCHANT / ADMIN / SYSTEM */
    private String actorRole;
    private String detail;
    private LocalDateTime createTime;
}
