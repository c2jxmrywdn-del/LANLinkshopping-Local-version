package com.lanlink.shopping.module.merchantexit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 退店配置（app.merchant-exit.*）。noticeDays 法定 30 天；演示可用分钟级 poll 触发公示到期。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.merchant-exit")
public class MerchantExitProperties {
    /** 公示期天数（电子商务法第16条 / 网络交易监督管理办法第23条） */
    private int noticeDays = 30;
    /** 时间闸门：最近一笔已完成订单距今天数下限；0=关闭该闸门 */
    private int minCompleteDays = 0;
    /** 入驻审核通过时首缴保证金（账面登记，非真实划转） */
    private BigDecimal depositAmount = new BigDecimal("2000.00");
    /** 公示到期扫表间隔 ms（默认 1 小时） */
    private long pollMs = 3600000L;
}
