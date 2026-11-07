package com.lanlink.shopping.module.tracking.event;

import org.springframework.context.ApplicationEvent;

/**
 * 运单发货事件（out/logistics-tracking-plan.md D3）。
 * 由 LogisticsWaybillService.ship()/shipSplit() 落库后发布（对服务仅注入 publisher +
 * 两行 publish 的改动）；tracking 模块 AFTER_COMMIT 监听，建首条补拉任务并发起订阅。
 * 发布方不感知 tracking 模块，模块依赖方向 service→event。
 * logisticsNo 允许为空（商家自送等 manual 单）。
 */
public class WaybillShippedEvent extends ApplicationEvent {

    private final Long waybillId;
    private final String orderNo;
    private final Long providerId;
    private final String logisticsNo;
    private final String waybillNo;

    public WaybillShippedEvent(Object source, Long waybillId, String orderNo, Long providerId,
                               String logisticsNo, String waybillNo) {
        super(source);
        this.waybillId = waybillId;
        this.orderNo = orderNo;
        this.providerId = providerId;
        this.logisticsNo = logisticsNo;
        this.waybillNo = waybillNo;
    }

    public Long getWaybillId() { return waybillId; }
    public String getOrderNo() { return orderNo; }
    public Long getProviderId() { return providerId; }
    public String getLogisticsNo() { return logisticsNo; }
    public String getWaybillNo() { return waybillNo; }
}
