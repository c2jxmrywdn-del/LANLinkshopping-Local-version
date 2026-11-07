package com.lanlink.shopping.module.tracking.adapter;

import java.util.List;

/**
 * 承运商/聚合商适配器统一接口（out/logistics-tracking-plan.md §6.1）。
 * 实现只做协议转换：subscribe/poll 的产物一律是 {@link NormalizedTraceNode}，
 * 落库统一走 LogisticsWaybillService.syncTrace()，适配器不得直接写轨迹/状态。
 */
public interface CarrierAdapter {

    TrackingChannel channel();

    /** 渠道凭证是否齐备；false 时上层顺延补拉不烧重试（P2 worker 语义） */
    boolean configured();

    /** 回调订阅注册（推送通道）；失败不阻塞发货，靠补拉兜底 */
    SubscribeResult subscribe(WaybillTrackInfo waybill);

    /** 定时补拉（主路径，webhook 不可达时的兜底通道） */
    PollResult poll(WaybillTrackInfo waybill);

    /** 渠道推送原文 → 规范化节点；解析异常由上层统一按「成功应答 + event failed」处理（§4.5） */
    List<NormalizedTraceNode> parse(String rawBody);

    /** 订阅注册结果 */
    record SubscribeResult(boolean success, String message) {
        public static SubscribeResult ok(String message) { return new SubscribeResult(true, message); }
        public static SubscribeResult fail(String message) { return new SubscribeResult(false, message); }
    }

    /** 补拉结果（NOT_CONFIGURED 语义供 P2 worker 顺延不烧重试） */
    record PollResult(boolean success, String errorMessage, List<NormalizedTraceNode> nodes) {
        public static PollResult ok(List<NormalizedTraceNode> nodes) { return new PollResult(true, null, nodes); }
        public static PollResult fail(String message) { return new PollResult(false, message, List.of()); }
        public static PollResult notConfigured() { return new PollResult(false, "NOT_CONFIGURED", List.of()); }
    }
}
