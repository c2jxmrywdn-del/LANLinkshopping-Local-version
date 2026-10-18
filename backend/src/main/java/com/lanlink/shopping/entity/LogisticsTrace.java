package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 物流轨迹节点 */
@Data
@TableName("t_logistics_trace")
public class LogisticsTrace {
    @TableId(type = IdType.AUTO)
    private Long traceId;
    private Long waybillId;
    private LocalDateTime traceTime;
    private String location;
    private String status;
    private String description;
    /** 0正常 1异常节点 */
    private Integer isAbnormal;
    /** api=接口同步 manual=人工录入 */
    private String source;
    private LocalDateTime createTime;
}
