package com.lanlink.shopping.module.pricemonitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品价格变动留痕（价格智能监控）
 * source: MERCHANT 商户调价 / ADMIN 平台调价 / SEED 演示种子
 */
@Data
@TableName("t_price_history")
public class PriceHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long prodId;
    private Long merId;
    private Long indId;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    /** 变动幅度 %：(新-旧)/旧 * 100 */
    private BigDecimal changePct;
    private String source;
    private Long operatorId;
    private LocalDateTime createTime;
}
