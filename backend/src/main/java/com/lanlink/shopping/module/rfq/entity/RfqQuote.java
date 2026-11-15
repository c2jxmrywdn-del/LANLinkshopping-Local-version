package com.lanlink.shopping.module.rfq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户报价。一户一报价（UNIQUE(rfq_id, mer_id)），必须锚定属于本商户的真实目录商品
 * （prod_id NOT NULL），否则接受后生成的订单商户侧 JOIN 不到、发不了货。
 *
 * <p>total_amount 由 unit_price × quantity 落库（scale 2），不信任客户端传入总额。
 * 接受动作用条件更新 WHERE status='SUBMITTED' 抢闸，兄弟报价同事务拒绝。
 */
@Data
@TableName("t_rfq_quote")
public class RfqQuote {
    @TableId(type = IdType.AUTO)
    private Long quoteId;
    private Long rfqId;
    private Long merId;
    private Long prodId;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    private BigDecimal totalAmount;
    private String remark;
    private LocalDateTime validUntil;
    /** SUBMITTED → ACCEPTED / REJECTED / WITHDRAWN / EXPIRED */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}