package com.lanlink.shopping.module.settlement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 结算账本配置（app.settlement.*）。
 *
 * <p>佣金率口径为百分比数值：7.00 表示 7%，与 t_merchant.commission_rate / t_merchant_earning.commission_rate
 * 的 DECIMAL(5,2) 列同口径，避免「小数率 vs 百分数」两套表示混用。
 *
 * <p>商户级覆盖优先于全局默认：商户 commission_rate 为 NULL 时才用 defaultCommissionRate。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.settlement")
public class SettlementProperties {
    /** 全局默认佣金率(%)，商户未覆盖时生效 */
    private BigDecimal defaultCommissionRate = new BigDecimal("7.00");
    /** 持有期天数：EARN 记入后 N 天无新增退款转 AVAILABLE */
    private int holdingDays = 7;
}
