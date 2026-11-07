package com.lanlink.shopping.module.tracking.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapter;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapterRegistry;
import com.lanlink.shopping.module.tracking.adapter.NormalizedTraceNode;
import com.lanlink.shopping.module.tracking.adapter.TrackingChannel;
import com.lanlink.shopping.module.tracking.adapter.TrackingStatusMapper;
import com.lanlink.shopping.module.tracking.domain.TrackingCallbackLog;
import com.lanlink.shopping.module.tracking.domain.TrackingEvent;
import com.lanlink.shopping.module.tracking.mapper.TrackingCallbackLogMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.security.CallbackDesensitizer;
import com.lanlink.shopping.module.tracking.security.WebhookVerifier;
import com.lanlink.shopping.module.tracking.security.WebhookVerifierRegistry;
import com.lanlink.shopping.service.LogisticsWaybillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回调摄入编排（out/logistics-tracking-plan.md §4 / P1 任务 4）：
 * 验签 → 脱敏 → 幂等 → 解析 → 复用既有 syncTrace 落轨迹。
 *
 * 关键语义：
 *  - 验签失败：记 FAIL 审计行（脱敏原文全量）→ 401，绝不 500（500 会让渠道按临时错误重推白耗配额，§4.3）；
 *  - 事件幂等：uk_event(provider_code, external_event_id)，重复推送 INSERT 冲突即按渠道语义
 *    返回成功应答、零重复解析/落轨迹（§3.2 幂等分层第 1 层）；
 *  - 解析/入库异常：统一返回成功应答 + tracking_event.status='failed'（永久错误重推无益，
 *    靠补拉兜底修正 + 看板可见，§4.5）；
 *  - 轨迹单一写路径（D2）：本服务不直接写 t_logistics_trace/t_logistics_waybill.status，
 *    一切推进经由 LogisticsWaybillService.syncTrace → LogisticsStateMachine。
 */
@Service
public class TrackingIngestService {

    private static final Logger log = LoggerFactory.getLogger(TrackingIngestService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 成功回调审计只存原文前 2KB 摘要（§4.3） */
    private static final int PASS_BODY_SNIPPET_CHARS = 2048;

    private final WebhookVerifierRegistry verifierRegistry;
    private final CarrierAdapterRegistry adapterRegistry;
    private final TrackingEventMapper eventMapper;
    private final TrackingCallbackLogMapper callbackLogMapper;
    private final LogisticsWaybillMapper waybillMapper;
    private final LogisticsWaybillService waybillService;
    private final CallbackDesensitizer desensitizer;

    public TrackingIngestService(WebhookVerifierRegistry verifierRegistry,
                                 CarrierAdapterRegistry adapterRegistry,
                                 TrackingEventMapper eventMapper,
                                 TrackingCallbackLogMapper callbackLogMapper,
                                 LogisticsWaybillMapper waybillMapper,
                                 LogisticsWaybillService waybillService,
                                 CallbackDesensitizer desensitizer) {
        this.verifierRegistry = verifierRegistry;
        this.adapterRegistry = adapterRegistry;
        this.eventMapper = eventMapper;
        this.callbackLogMapper = callbackLogMapper;
        this.waybillMapper = waybillMapper;
        this.waybillService = waybillService;
        this.desensitizer = desensitizer;
    }

    /** 渠道要求应答：httpStatus + body */
    public record CallbackAck(int httpStatus, Map<String, Object> body) {}

    /**
     * 处理一次渠道推送。
     *
     * @param providerCode 回调 URL {provider} 段（渠道词汇表，非承运商编码）
     * @param rawBody      推送原文（form 或 JSON）
     * @param token        回调 URL 路径段 token（无则为 null）
     * @param remoteIp     来源 IP（审计）
     */
    public CallbackAck handle(String providerCode, String rawBody, Map<String, String> headers,
                              String uri, String token, String remoteIp) {
        // 1. 验签（fail-closed；未知渠道无 verifier → 404）
        WebhookVerifier verifier = verifierRegistry.forProvider(providerCode);
        if (verifier == null) {
            logCallback(providerCode, remoteIp, TrackingCallbackLog.VERIFY_FAIL, 404,
                    desensitizer.summarize(rawBody, PASS_BODY_SNIPPET_CHARS), "no verifier for provider");
            return new CallbackAck(404, Map.of("success", false, "errorCode", "UNKNOWN_PROVIDER"));
        }
        WebhookVerifier.VerifyResult verified =
                verifier.verify(new WebhookVerifier.VerifyRequest(providerCode, rawBody, headers, uri, token));
        if (!verified.pass()) {
            // FAIL 行存脱敏原文全量，供追查伪造来源与渠道联调排障（§4.3）
            logCallback(providerCode, remoteIp, TrackingCallbackLog.VERIFY_FAIL, 401,
                    desensitizer.desensitize(rawBody), verified.reason());
            log.warn("[tracking] 回调验签失败 provider={} ip={} reason={}", providerCode, remoteIp, verified.reason());
            return new CallbackAck(401, Map.of("success", false, "errorCode", "SIGN_INVALID"));
        }

        // 2. PASS 审计行（只存前 2KB 脱敏摘要）
        logCallback(providerCode, remoteIp, TrackingCallbackLog.VERIFY_PASS, 200,
                desensitizer.summarize(rawBody, PASS_BODY_SNIPPET_CHARS), null);

        TrackingChannel channel = TrackingChannel.fromCode(providerCode);
        String externalEventId = resolveExternalEventId(providerCode, rawBody);
        String logisticsNo = extractLogisticsNo(rawBody);
        String maskedBody = desensitizer.desensitize(rawBody);

        // 3. 事件幂等（uk_event 冲突即幂等返回成功应答，§3.2）
        TrackingEvent event = new TrackingEvent();
        event.setProviderCode(providerCode);
        event.setExternalEventId(externalEventId);
        event.setLogisticsNo(logisticsNo);
        event.setEventType(TrackingEvent.TYPE_CALLBACK);
        event.setStatus(TrackingEvent.STATUS_RECEIVED);
        event.setRawBody(maskedBody);
        event.setNormalizedCount(0);
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException dup) {
            log.info("[tracking] 渠道 {} 事件 {} 重复推送，幂等返回成功应答", providerCode, externalEventId);
            return successAck(channel, null);
        }

        // 4. 解析 + 落轨迹；任何异常 → event failed + 成功应答（§4.5：永久错误重推无益，补拉兜底）
        try {
            CarrierAdapter adapter = adapterRegistry.adapterFor(channel);
            if (adapter == null) {
                return failEvent(event, "channel adapter not available (P2 skeleton)", channel);
            }
            List<NormalizedTraceNode> nodes = adapter.parse(rawBody);
            // 七态收敛（P2/P3 任务 3）：节点状态统一在映射层落位并随节点显式下传，
            // event_time 取渠道侧最新节点时间（入库延迟 P95 指标分母）
            event.setEventTime(TrackingStatusMapper.maxNodeTime(nodes));

            // 运单解析两级匹配：优先第三方快递单号（logistics_no，uk_logistics_no 唯一）；
            // 未命中按平台运单号（waybill_no）回退——渠道/演示推送常携带 LL 运单号，
            // 而发货未传 logisticsNo 时该列保持 NULL（uk 允许多行 NULL），缺回退会导致全部推送 failEvent
            LogisticsWaybill waybill = null;
            if (logisticsNo != null) {
                waybill = waybillMapper.selectOne(
                        Wrappers.<LogisticsWaybill>lambdaQuery().eq(LogisticsWaybill::getLogisticsNo, logisticsNo));
                if (waybill == null) {
                    waybill = waybillMapper.selectOne(Wrappers.<LogisticsWaybill>lambdaQuery()
                            .eq(LogisticsWaybill::getWaybillNo, logisticsNo).last("LIMIT 1"));
                }
            }
            if (waybill == null) {
                return failEvent(event, "waybill not found for logisticsNo=" + logisticsNo, channel);
            }

            // 节点级去重/状态机推进/告警全部复用既有 syncTrace（D2 单一写路径）
            Map<String, Object> result = waybillService.syncTrace(waybill.getWaybillId(),
                    TrackingStatusMapper.toSyncNodeMaps(nodes));
            int imported = result != null && result.get("imported") instanceof Integer n ? n : 0;

            event.setWaybillId(waybill.getWaybillId());
            event.setStatus(TrackingEvent.STATUS_NORMALIZED);
            event.setNormalizedCount(imported);
            eventMapper.updateById(event);
            return successAck(channel, null);
        } catch (Exception e) {
            log.warn("[tracking] 渠道 {} 事件 {} 解析/入库失败（成功应答+failed，补拉兜底）: {}",
                    providerCode, externalEventId, e.getMessage());
            return failEvent(event, truncate(e.getMessage(), 480), channel);
        }
    }

    private CallbackAck failEvent(TrackingEvent event, String error, TrackingChannel channel) {
        event.setStatus(TrackingEvent.STATUS_FAILED);
        event.setErrorMsg(error);
        try {
            eventMapper.updateById(event);
        } catch (Exception e) {
            log.warn("[tracking] failed 事件回写失败 eventId={}: {}", event.getEventId(), e.getMessage());
        }
        return successAck(channel, null);
    }

    /** 渠道语义成功应答（§4.5）：快递100 {"result":true,"returnCode":"200"}（abort 亦走此应答终止重推）；其余 {"success":true} */
    private CallbackAck successAck(TrackingChannel channel, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (channel == TrackingChannel.KUAIDI100) {
            body.put("result", true);
            body.put("returnCode", "200");
        } else {
            body.put("success", true);
        }
        if (extra != null) body.putAll(extra);
        return new CallbackAck(200, body);
    }

    private void logCallback(String providerCode, String remoteIp, String verifyResult, int httpStatus,
                             String maskedBody, String failReason) {
        try {
            TrackingCallbackLog row = new TrackingCallbackLog();
            row.setProviderCode(providerCode);
            row.setRemoteIp(remoteIp);
            row.setVerifyResult(verifyResult);
            row.setHttpStatus(httpStatus);
            row.setBodyRaw(maskedBody);
            row.setFailReason(failReason);
            callbackLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[tracking] 回调审计落库失败 provider={}: {}", providerCode, e.getMessage());
        }
    }

    /** 显式 eventId 优先；无显式 ID（如快递100 推送）取 provider+正文 SHA-256 前 32 hex 作幂等键 */
    private String resolveExternalEventId(String providerCode, String rawBody) {
        try {
            if (rawBody != null && !rawBody.isBlank()) {
                JsonNode root = JSON.readTree(rawBody);
                JsonNode id = root.get("eventId");
                if (id != null && !id.isNull() && !id.asText().isBlank()) {
                    return truncate(id.asText().trim(), 64);
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 正文走摘要键
        }
        return sha256Short(providerCode + "|" + (rawBody == null ? "" : rawBody));
    }

    private String extractLogisticsNo(String rawBody) {
        try {
            if (rawBody != null && !rawBody.isBlank()) {
                JsonNode root = JSON.readTree(rawBody);
                for (String field : new String[]{"logisticsNo", "number", "num"}) {
                    JsonNode v = root.get(field);
                    if (v != null && !v.isNull() && !v.asText().isBlank()) return truncate(v.asText().trim(), 64);
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 正文：运单解析失败会在失败分支落 failed 事件
        }
        return null;
    }

    private static String sha256Short(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
