package com.lanlink.shopping.module.tracking.adapter;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 规范化轨迹节点——列结构对齐 t_logistics_trace（trace_time/location/status/description/
 * is_abnormal/longitude/latitude/city/hub_type），由各 CarrierAdapter.parse/poll 产出，
 * 统一经 LogisticsWaybillService.syncTrace() 落库（规划 D2 单一写路径）。
 * status 允许为空：留空时由 syncTrace 内的 LogisticsTraceParser 按描述关键词推导；
 * 渠道结构化状态优先的映射（TrackingStatusMapper）在 P3 落地。
 */
@Data
public class NormalizedTraceNode {

    private LocalDateTime traceTime;
    private String location;
    /** 平台 9 态（LogisticsStateMachine 常量），可空 */
    private String status;
    private String description;
    private Boolean isAbnormal;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String city;
    /** warehouse/hub/transfer/terminal/client */
    private String hubType;

    public static NormalizedTraceNode of(LocalDateTime traceTime, String location, String description) {
        NormalizedTraceNode node = new NormalizedTraceNode();
        node.setTraceTime(traceTime);
        node.setLocation(location);
        node.setDescription(description);
        return node;
    }

    private static final DateTimeFormatter[] FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    /**
     * 渠道时间文本容错解析；全部格式失败回退当前时间（保守不阻断同步，
     * 与 LogisticsTraceParser 对未知描述的兜底口径一致）。
     */
    public static LocalDateTime parseTime(String text) {
        if (text != null && !text.isBlank()) {
            String t = text.trim();
            for (DateTimeFormatter fmt : FORMATS) {
                try {
                    return LocalDateTime.parse(t, fmt);
                } catch (DateTimeParseException ignored) {
                    // 尝试下一格式
                }
            }
        }
        return LocalDateTime.now();
    }
}
