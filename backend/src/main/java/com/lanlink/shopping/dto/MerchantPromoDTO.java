package com.lanlink.shopping.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商户自建促销请求（店铺促销：scope 固定 prod，商品必须全部属于本商户；
 * 创建后 review_status=0 待平台审核，审核通过且上架后生效）
 */
@Data
public class MerchantPromoDTO {
    private String title;
    /** 仅允许 full_reduce / discount / flash_sale */
    private String type;
    private String description;
    private String accent;
    /** 前端表单 value-format 为空格分隔（YYYY-MM-DD HH:mm:ss），显式声明反序列化格式 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    /** 门槛金额（满 X 可用，按命中促销的商品行金额判定） */
    private BigDecimal threshold;
    /** 优惠金额（满减）；折扣类为优惠封顶金额 */
    private BigDecimal benefitAmount;
    /** 折扣率（0.95 = 95 折） */
    private BigDecimal discountRate;
    /** 参与促销的商品ID列表（必须全部属于本商户，越权直接拒绝） */
    private List<Long> prodIds;
}
