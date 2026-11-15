package com.lanlink.shopping.module.rfq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 买家询价单（RFQ）。买家描述采购需求，商户按目录商品报价，接受后转订单。
 *
 * <p>状态机：PUBLISHED → QUOTED（首条报价）→ ACCEPTED → ORDERED；
 * PUBLISHED/QUOTED → EXPIRED（扫表过 quote_deadline）或 CANCELLED（买家撤回）。
 */
@Data
@TableName("t_rfq")
public class Rfq {
    @TableId(type = IdType.AUTO)
    private Long rfqId;
    private String rfqNo;
    private Long userId;
    private String title;
    private String description;
    /** 意向目录商品（可空：买家也可仅描述需求，由报价锚定具体商品） */
    private Long prodId;
    private BigDecimal quantity;
    private String unit;
    private LocalDateTime quoteDeadline;
    /** 报价户数上限（默认 10，我方设计值） */
    private Integer maxQuotes;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}