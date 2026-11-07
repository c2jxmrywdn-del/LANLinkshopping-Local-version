package com.lanlink.shopping.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 任务激励流水（F3 最小闭环，activity-plan.md §4.2 t_task_record）。
 * 字段与规划 DDL 逐列对齐：id/user_id/task_key/ref_no/points/growth/create_time。
 * 任务不是促销——不进 t_campaign 七类、不进 isPromoType/matchTab，奖励积分经
 * MembershipService.awardPoints（refNo 幂等）落账。
 * 防重：唯一键 uk_task_user_key_ref(user_id, task_key, ref_no) 数据库兜底，重复事件只落一次。
 */
@Data
@TableName("t_task_record")
public class TaskRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 任务标识（TaskCatalog：first_order / share_product） */
    private String taskKey;
    /** 幂等引用：首单=orderNo；分享=回执标识:yyyyMMdd（服务端拼接，不信任前端日期） */
    private String refNo;
    /** 奖励积分（与发放流水一致） */
    private Integer points;
    /** 奖励成长值（规划档位快照） */
    private Integer growth;
    private LocalDateTime createTime;
}
