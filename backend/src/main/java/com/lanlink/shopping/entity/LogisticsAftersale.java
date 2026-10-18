package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 物流售后 */
@Data
@TableName("t_logistics_aftersale")
public class LogisticsAftersale {
    @TableId(type = IdType.AUTO)
    private Long aftersaleId;
    private String aftersaleNo;
    private Long waybillId;
    private String orderNo;
    /** expedite催件 trace查件 claim赔付 reship补发 return_freight退回运费 */
    private String aftersaleType;
    private String description;
    /** 凭证JSON（存储层内部格式，不外发） */
    @JsonIgnore
    private String evidenceJson;
    private BigDecimal claimAmount;
    /** pending/processing/approved/rejected/done */
    private String status;
    private Long processorId;
    private String processNote;
    private LocalDateTime processTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 逻辑删除标记（存储层内部字段，不外发） */
    @JsonIgnore
    private Integer deleted;
}
