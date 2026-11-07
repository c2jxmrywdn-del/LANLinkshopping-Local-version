package com.lanlink.shopping.module.tracking.adapter;

import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道路由注册表：provider_code/渠道编码 → CarrierAdapter。
 * 路由规则（规划 §2.2）：TrackingProperties.channelRoutes 显式映射优先；
 * provider.api_type='mock' 兜底 MockChannel（种子服务商 SF/DB/JD/ZT 均为 mock，
 * 保证零外部依赖演示）；再按 providerCode 本身是渠道编码兜底。
 */
@Component
public class CarrierAdapterRegistry {

    private final Map<TrackingChannel, CarrierAdapter> adapters = new EnumMap<>(TrackingChannel.class);
    private final TrackingProperties properties;

    public CarrierAdapterRegistry(List<CarrierAdapter> adapterList, TrackingProperties properties) {
        this.properties = properties;
        for (CarrierAdapter adapter : adapterList) {
            adapters.put(adapter.channel(), adapter);
        }
    }

    /** 渠道 → 适配器；未实现的渠道（P1 的 kdniao/track17/aftership）返回 null */
    public CarrierAdapter adapterFor(TrackingChannel channel) {
        return channel == null ? null : adapters.get(channel);
    }

    /**
     * 承运商编码 + api_type → 渠道。无匹配返回 null（调用方按 no_channel 处理，
     * 不静默归一，避免把真实通道误路由到 Mock）。
     */
    public TrackingChannel resolveChannel(String providerCode, String apiType) {
        if (providerCode != null && !providerCode.isBlank()) {
            String routed = properties.getChannelRoutes().get(providerCode);
            TrackingChannel mapped = TrackingChannel.fromCode(routed);
            if (mapped != null) return mapped;
        }
        if ("mock".equalsIgnoreCase(apiType)) return TrackingChannel.MOCK;
        return TrackingChannel.fromCode(providerCode);
    }
}
