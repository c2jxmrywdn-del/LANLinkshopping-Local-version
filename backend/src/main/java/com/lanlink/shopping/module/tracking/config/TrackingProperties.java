package com.lanlink.shopping.module.tracking.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物流跟踪配置（logistics.tracking.*，out/logistics-tracking-plan.md §6.1）。
 * 红线（D6）：真实渠道密钥一律环境变量注入，不入库不入仓；
 * 默认值仅承载 dev 演示语义（callbackUrlToken），生产必须经 TRACKING_* 覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "logistics.tracking")
public class TrackingProperties {

    private static final Logger log = LoggerFactory.getLogger(TrackingProperties.class);

    /** 历史默认值：仅用于识别"仍是演示默认值"，不再作为可用凭据（UrlTokenVerifier 会直接拒绝） */
    public static final String LEGACY_DEV_TOKEN = "dev-callback-token";

    /** 回调 URL token 最小长度：短于该值视为弱凭据 */
    public static final int MIN_CALLBACK_TOKEN_LENGTH = 16;

    /**
     * 占位符标记：`.env.example` 里的 `your_callback_url_token_here` 这类值
     * 长度足够但显然不是真实随机串。只做「明显占位」识别，不做熵估算——
     * 熵估算容易把真实可用的 hex 误判。
     */
    private static final String[] PLACEHOLDER_MARKERS = {
            "your_", "_here", "changeme", "change_me", "placeholder", "example.com",
    };

    @Autowired(required = false)
    private Environment environment;

    /** 跟踪链路总开关（P4 灰度演练用：false 时回调 410/listener 短路，ship 主流程零影响） */
    private boolean enabled = true;

    /** 成功回调/成功补拉后顺延 tracking_sync_task.next_poll_time 的间隔 ms（cron 巡检兜底） */
    private long pollIntervalMs = 600_000L;

    /**
     * 补拉巡检 cron（默认每 10 分钟，对齐 SLA watchdog 的 cron 调度写法；不引入 XXL-Job，
     * 多实例部署预留 ShedLock 注释位，见 TrackingSyncWorker）。
     */
    private String pollCron = "0 */10 * * * ?";

    /** 补拉触发静默阈值（小时）：运单最后渠道事件超此时长仍未签收 → 兜底补拉 */
    private int pollStaleHours = 4;

    /** 主通道连续失败次数阈值：达到即切换备用通道（fail_count 热扩列累计） */
    private int primaryFailThreshold = 3;

    /** 渠道主→备映射（key=主渠道编码 value=备用渠道编码，如 kuaidi100→kdniao）；未配置的渠道不切换 */
    private Map<String, String> fallbackRoutes = new LinkedHashMap<>();

    /** 异常识别巡检 cron（48h 无更新 → EXCEPTION 告警 + 看板；对齐 SLA watchdog 写法） */
    private String exceptionCron = "0 */30 * * * ?";

    /** 异常识别静默阈值（小时）：运单最后动态超此时长 → timeout_no_update 告警 + 看板 */
    private int exceptionStaleHours = 48;

    /** 人工复核静默阈值（小时）：签收后超此时长无更新且 e-POD 未复核 → 人工复核队列 */
    private int reviewStaleHours = 24;

    /** 监控指标统计窗口（小时，GET /admin/logistics/metrics） */
    private int metricsWindowHours = 24;

    /** 对账自愈 job cron（P2 随补拉落地） */
    private String reconCron = "0 0 3 * * ?";

    /** 回调审计日志保留天数（防表膨胀） */
    private int callbackLogRetentionDays = 30;

    /**
     * 回调 URL 路径段共享 token（Mock/17TRACK 共用，UrlTokenVerifier 校验）。
     * 无内置默认值：未配置即 fail-closed（UrlTokenVerifier 拒绝全部 URL-token 渠道回调）。
     * 生产必须经 TRACKING_CALLBACK_URL_TOKEN 注入 32 位以上随机 hex。
     */
    private String callbackUrlToken;

    /**
     * 承运商编码 → 渠道编码路由表（如 SF→kuaidi100）。
     * provider.api_type='mock' 的兜底在 CarrierAdapterRegistry，不在此表硬编码。
     */
    private Map<String, String> channelRoutes = new LinkedHashMap<>();

    private Kuaidi100 kuaidi100 = new Kuaidi100();

    /**
     * 弱 token 判定：空 / 历史默认值 / 短于 16 位。
     *
     * <p>静态方法便于单测直接断言，不依赖 Spring 上下文。
     */
    public static boolean isWeakCallbackToken(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        String trimmed = token.trim();
        if (LEGACY_DEV_TOKEN.equalsIgnoreCase(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return trimmed.length() < MIN_CALLBACK_TOKEN_LENGTH;
    }

    /**
     * 启动期校验：prod 下弱 token 直接 fail-fast；dev 下仅告警。
     *
     * <p>真正的拦截在 {@code UrlTokenVerifier}：无论 profile，弱 token 一律验签失败，
     * 因此 dev 启动不会被打断，但历史默认值也无法再伪造回调（审计 P1-3）。
     */
    @PostConstruct
    void validateCallbackToken() {
        if (!enabled) {
            return;
        }
        boolean prod = environment != null
                && java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isWeakCallbackToken(callbackUrlToken)) {
            String message = "logistics.tracking.callback-url-token 未配置或过弱（需 >= "
                    + MIN_CALLBACK_TOKEN_LENGTH + " 位随机串，且不得为 " + LEGACY_DEV_TOKEN + "）；"
                    + "URL-token 渠道（mock/track17）回调将被拒绝";
            if (prod) {
                throw new IllegalStateException(message + "。生产环境禁止使用默认/短 token。");
            }
            log.warn("[tracking] {}（当前 profile 非 prod，仅告警；回调仍会被验签拒绝）", message);
        }
    }

    @Data
    public static class Kuaidi100 {
        /** 企业版授权 key（订阅推送需企业版，openQuestion #1） */
        private String key;
        /** 企业版 customer 编号 */
        private String customer;
        /** 推送方向验签盐：sign=MD5(param+salt) 32 位大写 */
        private String salt;
        /** 请求端签名算法：官方 signType=SHA256 优先，MD5 兜底 */
        private String signType = "SHA256";
        /** 订阅推送接口 */
        private String subscribeUrl = "https://poll.kuaidi100.com/poll/api.do";
        /** 即时查询接口 */
        private String queryUrl = "https://poll.kuaidi100.com/poll/query.do";
        /** 对外可达回调基地址（如 https://host/api/logistics/callback/kuaidi100）；留空=仅走补拉 */
        private String callbackUrl;
        /**
         * 承运商编码 → 快递100 公司编码映射（规划 §10：两平台编码不同，禁止凭记忆硬编码；
         * 如跨越速运=kuayue 全小写）。缺省回退 providerCode 小写，联调时按官方编码表补全。
         */
        private Map<String, String> companyCodes = new LinkedHashMap<>();
    }
}
