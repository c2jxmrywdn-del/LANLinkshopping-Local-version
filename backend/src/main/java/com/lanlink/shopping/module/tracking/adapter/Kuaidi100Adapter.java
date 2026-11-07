package com.lanlink.shopping.module.tracking.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.security.Kuaidi100Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快递100 适配器（out/logistics-tracking-plan.md P1 任务 5）：subscribe/poll/parse 全量。
 * HTTP 用 java.net.http.HttpClient 对齐仓内惯例（SupportLearningSandboxController、
 * module/aikb/EmbeddingService 等）；零新增 Maven 依赖。
 *
 * 请求端签名：官方 signType=SHA256 优先（HMAC-SHA256 大写 hex），MD5 兜底；
 * 推送回调方向固定 sign=MD5(param+salt)，验签在 Kuaidi100Verifier（不可升级，§10）。
 * 注意：无真实企业版账号（openQuestion #1），SHA256 签名的拼接顺序以会员中心下发文档
 * 为准，联调时校正——MD5(param+key+customer) 为官方公开口径。
 *
 * 渠道应答语义（§4.5，实现在 TrackingCallbackController/IngestService）：
 * 成功 {"result":true,"returnCode":"200"}；abort（3 天无记录/60 天无变化）也须返回成功，
 * 否则渠道无法终止重推；推送失败 30 分钟后重推、3 次放弃。
 */
@Component
public class Kuaidi100Adapter implements CarrierAdapter {

    private static final Logger log = LoggerFactory.getLogger(Kuaidi100Adapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final TrackingProperties properties;

    public Kuaidi100Adapter(TrackingProperties properties) {
        this.properties = properties;
    }

    @Override
    public TrackingChannel channel() {
        return TrackingChannel.KUAIDI100;
    }

    @Override
    public boolean configured() {
        TrackingProperties.Kuaidi100 cfg = properties.getKuaidi100();
        return notBlank(cfg.getKey()) && notBlank(cfg.getCustomer());
    }

    @Override
    public SubscribeResult subscribe(WaybillTrackInfo waybill) {
        if (!configured()) return SubscribeResult.fail("kuaidi100 key/customer not configured");
        if (waybill == null || !notBlank(waybill.logisticsNo())) {
            return SubscribeResult.fail("kuaidi100 subscribe missing logisticsNo");
        }
        TrackingProperties.Kuaidi100 cfg = properties.getKuaidi100();
        try {
            Map<String, Object> param = new LinkedHashMap<>();
            param.put("company", companyCode(waybill.providerCode()));
            param.put("number", waybill.logisticsNo());
            if (notBlank(cfg.getSalt())) param.put("salt", cfg.getSalt());
            if (notBlank(cfg.getCallbackUrl())) param.put("callbackurl", cfg.getCallbackUrl());
            param.put("resultv2", "4");
            String paramJson = JSON.writeValueAsString(param);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("auth", cfg.getKey());
            form.put("signType", cfg.getSignType());
            form.put("sign", sign(paramJson));
            form.put("param", paramJson);
            form.put("customer", cfg.getCustomer());
            String resp = postForm(cfg.getSubscribeUrl(), form);
            JsonNode root = JSON.readTree(resp);
            boolean ok = root.path("result").asBoolean(false)
                    && "200".equals(root.path("returnCode").asText(""));
            return ok ? SubscribeResult.ok("kuaidi100 subscribed")
                    : SubscribeResult.fail("kuaidi100 subscribe rejected: "
                        + root.path("message").asText(resp));
        } catch (Exception e) {
            log.warn("[tracking] 快递100 订阅失败 waybill={}，退化为补拉兜底: {}",
                    waybill.waybillNo(), e.getMessage());
            return SubscribeResult.fail("kuaidi100 subscribe error: " + e.getMessage());
        }
    }

    @Override
    public PollResult poll(WaybillTrackInfo waybill) {
        if (!configured()) return PollResult.notConfigured();
        if (waybill == null || !notBlank(waybill.logisticsNo())) {
            return PollResult.fail("kuaidi100 poll missing logisticsNo");
        }
        TrackingProperties.Kuaidi100 cfg = properties.getKuaidi100();
        try {
            String paramJson = JSON.writeValueAsString(Map.of(
                    "company", companyCode(waybill.providerCode()),
                    "number", waybill.logisticsNo()));
            Map<String, String> form = new LinkedHashMap<>();
            form.put("auth", cfg.getKey());
            form.put("signType", cfg.getSignType());
            form.put("sign", sign(paramJson));
            form.put("param", paramJson);
            form.put("customer", cfg.getCustomer());
            String resp = postForm(cfg.getQueryUrl(), form);
            JsonNode root = JSON.readTree(resp);
            if (!"ok".equalsIgnoreCase(root.path("message").asText(""))) {
                return PollResult.fail("kuaidi100 poll: " + root.path("message").asText(resp));
            }
            return PollResult.ok(toNodes(root.get("data")));
        } catch (Exception e) {
            log.warn("[tracking] 快递100 补拉失败 waybill={}: {}", waybill.waybillNo(), e.getMessage());
            return PollResult.fail("kuaidi100 poll error: " + e.getMessage());
        }
    }

    /**
     * 推送原文（param JSON）→ 规范化节点。
     * status=abort（3 天无记录/60 天无变化渠道终止）返回空节点列表——
     * 上层仍返回成功应答，否则渠道无法终止重推（§4.5）。
     */
    @Override
    public List<NormalizedTraceNode> parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return List.of();
        JsonNode root;
        try {
            root = JSON.readTree(rawBody);
        } catch (Exception e) {
            throw new IllegalArgumentException("kuaidi100 callback body is not valid JSON", e);
        }
        String status = root.path("status").asText("");
        if ("abort".equalsIgnoreCase(status)) {
            log.info("[tracking] 快递100 推送 abort（渠道终止跟踪），返回成功应答终止重推");
            return List.of();
        }
        return toNodes(root.get("data"));
    }

    private List<NormalizedTraceNode> toNodes(JsonNode data) {
        List<NormalizedTraceNode> out = new ArrayList<>();
        if (data == null || !data.isArray()) return out;
        for (JsonNode n : data) {
            // 官方推荐 ftime（标准化时间），缺失回退 time
            String time = firstNonBlank(n.path("ftime").asText(null), n.path("time").asText(null));
            String context = n.path("context").asText("");
            if ((context == null || context.isBlank()) && time == null) continue;
            out.add(NormalizedTraceNode.of(
                    NormalizedTraceNode.parseTime(time),
                    n.path("location").asText(null),
                    context == null ? "" : context));
        }
        return out;
    }

    private String companyCode(String providerCode) {
        String mapped = properties.getKuaidi100().getCompanyCodes().get(providerCode);
        return mapped != null ? mapped
                : (providerCode == null ? "" : providerCode.toLowerCase());
    }

    /** 请求端签名：SHA256=HMAC-SHA256 大写 hex（key 为密钥），MD5=MD5 大写 */
    private String sign(String param) {
        TrackingProperties.Kuaidi100 cfg = properties.getKuaidi100();
        String plain = param + cfg.getKey() + cfg.getCustomer();
        if ("MD5".equalsIgnoreCase(cfg.getSignType())) {
            return Kuaidi100Verifier.md5Upper(plain);
        }
        return hmacSha256Upper(cfg.getKey(), plain);
    }

    /** HMAC-SHA256 大写 hex——公开供单测断言签名格式 */
    public static String hmacSha256Upper(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String postForm(String url, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("kuaidi100 http " + resp.statusCode());
        }
        return resp.body();
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }
}
