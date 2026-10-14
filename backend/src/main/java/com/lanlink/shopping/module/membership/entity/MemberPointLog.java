package com.lanlink.shopping.module.membership.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 会员积分流水（营销中台·会员系统）
 * change_val 正负号表示获得/消耗。
 */
@Data
@TableName("t_member_point_log")
public class MemberPointLog {
    @TableId(type = IdType.AUTO)
    private Long logId;
    private Long userId;
    private String changeType;
    private Integer changeVal;
    private String refOrderNo;
    private String remark;
    private LocalDateTime createTime;
}
