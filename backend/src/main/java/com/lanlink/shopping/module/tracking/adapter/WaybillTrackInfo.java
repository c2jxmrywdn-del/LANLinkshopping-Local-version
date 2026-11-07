package com.lanlink.shopping.module.tracking.adapter;

/**
 * 运单跟踪档案：适配器 subscribe/poll 的最小输入。
 * providerCode 是承运商编码（SF/DB/JD/ZT...），渠道归属由 CarrierAdapterRegistry 归一。
 */
public record WaybillTrackInfo(
        Long waybillId,
        String waybillNo,
        String orderNo,
        Long providerId,
        String providerCode,
        String logisticsNo
) {
}
