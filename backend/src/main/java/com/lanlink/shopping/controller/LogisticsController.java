package com.lanlink.shopping.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.LogisticsWaybillService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物流运单（商家发货 + 轨迹查询）
 */
@RestController
@RequestMapping("/logistics")
public class LogisticsController {

    private final LogisticsWaybillService waybillService;
    private final com.lanlink.shopping.service.LogisticsCarrierRecommenderService carrierRecommenderService;
    private final com.lanlink.shopping.service.LogisticsSlaWatchdogService slaWatchdogService;
    /** 高德商业图源凭据：仅由后端持有（application.yml ← AMAP_KEY/AMAP_SECURITY_JS_CODE），登录后经 /map-config 下发 */
    private final String amapKey;
    private final String amapSecurityJsCode;

    public LogisticsController(LogisticsWaybillService waybillService,
                               com.lanlink.shopping.service.LogisticsCarrierRecommenderService carrierRecommenderService,
                               com.lanlink.shopping.service.LogisticsSlaWatchdogService slaWatchdogService,
                               @org.springframework.beans.factory.annotation.Value("${app.map.amap.key:}") String amapKey,
                               @org.springframework.beans.factory.annotation.Value("${app.map.amap.security-js-code:}") String amapSecurityJsCode) {
        this.waybillService = waybillService;
        this.carrierRecommenderService = carrierRecommenderService;
        this.slaWatchdogService = slaWatchdogService;
        this.amapKey = amapKey;
        this.amapSecurityJsCode = amapSecurityJsCode;
    }

    /** 商家发货（创建运单） */
    public record ShipBody(String orderNo, Long providerId, Long templateId, String logisticsNo,
                           BigDecimal weight, BigDecimal volume, Boolean remote, Integer quantity,
                           String senderName, String senderPhone, String senderAddress) {}

    @PostMapping("/ship")
    @RequirePerm("merchant:manage")
    public R<LogisticsWaybill> ship(@Valid @RequestBody ShipBody body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        LogisticsWaybillService.ShipRequest req = new LogisticsWaybillService.ShipRequest(
                body.orderNo(), body.providerId(), body.templateId(), body.logisticsNo(),
                body.weight(), body.volume(), body.remote(), body.quantity(),
                body.senderName(), body.senderPhone(), body.senderAddress());
        return R.ok("发货成功", waybillService.ship(req, userId, request));
    }

    /** 多包裹拆单发货请求体 */
    public record SplitShipBody(
            String orderNo,
            Long providerId,
            Long templateId,
            String logisticsNo,
            BigDecimal weight,
            BigDecimal volume,
            Boolean remote,
            String senderName,
            String senderPhone,
            String senderAddress,
            String transportMode,
            String packageNo,
            Integer packageIndex,
            Integer packageTotal,
            List<LogisticsWaybillService.SplitItem> items,
            List<LogisticsWaybillService.CoaDocItem> coaDocs,
            com.lanlink.shopping.service.LogisticsFreightCalculator.VasRequest vas
    ) {}

    /** 工业品多包裹拆单发货（按 SKU / 分批件数拆分出库，支持绑定 CoA 质检单与 VAS 增值服务） */
    @PostMapping("/ship/split")
    @RequirePerm("merchant:manage")
    public R<LogisticsWaybill> shipSplit(@Valid @RequestBody SplitShipBody body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        LogisticsWaybillService.SplitShipRequest req = new LogisticsWaybillService.SplitShipRequest(
                body.orderNo(), body.providerId(), body.templateId(), body.logisticsNo(),
                body.weight(), body.volume(), body.remote(),
                body.senderName(), body.senderPhone(), body.senderAddress(),
                body.transportMode(), body.packageNo(), body.packageIndex(), body.packageTotal(),
                body.items(), body.coaDocs(), body.vas());
        return R.ok("拆单发货成功", waybillService.shipSplit(req, userId, request));
    }

    /** 现场 e-POD 签收回单提交（买家/现场人员上传公章照片与实物照片） */
    @PostMapping("/pod/upload")
    public R<com.lanlink.shopping.entity.LogisticsPodRecord> uploadPod(
            @Valid @RequestBody LogisticsWaybillService.PodUploadRequest body,
            HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("现场 e-POD 回执提交成功，等待验签", waybillService.uploadPod(body, userId, request));
    }

    /** 商户 / 平台运营在线验签 e-POD（审核通过流转终态，驳回要求重新拍照） */
    @PostMapping("/pod/audit/{waybillId}")
    @RequirePerm("merchant:manage")
    public R<LogisticsWaybill> auditPod(
            @PathVariable Long waybillId,
            @Valid @RequestBody LogisticsWaybillService.PodAuditRequest body,
            HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("e-POD 验签审核完成", waybillService.auditPod(waybillId, body, userId, request));
    }

    /** 订单全景多包裹与动态 GIS 路线聚合查询（买家本人/归属商户/平台运营均可访问） */
    @GetMapping("/order-waybills/{orderNo}")
    public R<LogisticsWaybillService.OrderWaybillAggregation> orderWaybills(@PathVariable String orderNo,
                                                                          HttpServletRequest request) {
        boolean isAdmin = UserContext.identity(request) == UserIdentity.ADMIN;
        Long userId = UserContext.currentUserId(request);
        return R.ok(waybillService.orderWaybills(orderNo, userId, isAdmin));
    }

    /** 状态流转（商家/运营手工操作，如确认签收） */
    @PostMapping("/transit/{waybillId}")
    @RequirePerm("merchant:manage")
    public R<LogisticsWaybill> transit(@PathVariable Long waybillId,
                                       @RequestParam String status,
                                       @RequestParam(required = false) String note,
                                       HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("状态更新成功", waybillService.transit(waybillId, status, note, userId, request));
    }

    /** 运单分页（按操作者身份隔离：运营全量 / 商户本店 / 买家本人订单；可按状态/订单号/服务商过滤） */
    @GetMapping("/waybill/list")
    public R<Page<LogisticsWaybill>> list(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String orderNo,
                                          @RequestParam(required = false) Long providerId,
                                          HttpServletRequest request) {
        // 身份由 AuthInterceptor 实时识别，避免硬编码管理员角色 ID
        boolean isAdmin = UserContext.identity(request) == UserIdentity.ADMIN;
        return R.ok(waybillService.page(page, size, status, orderNo, providerId,
                UserContext.currentUserId(request), isAdmin));
    }

    /** 运单轨迹（买家本人/订单归属商户/平台运营；按运单→订单归属校验，非属主拒绝）。路径段支持运单号或运单 ID */
    @GetMapping("/trace/{waybillIdOrNo}")
    public R<List<LogisticsTrace>> traces(@PathVariable String waybillIdOrNo, HttpServletRequest request) {
        boolean isAdmin = UserContext.identity(request) == UserIdentity.ADMIN;
        return R.ok(waybillService.traces(waybillIdOrNo, UserContext.currentUserId(request), isAdmin));
    }

    /** 高德商业图源配置（登录用户可拉取）：凭据仅存后端，未配置时返回空值，前端回退内置矢量底图 */
    @GetMapping("/map-config")
    public R<Map<String, String>> mapConfig() {
        Map<String, String> out = new HashMap<>();
        out.put("key", amapKey == null ? "" : amapKey.trim());
        out.put("securityJsCode", amapSecurityJsCode == null ? "" : amapSecurityJsCode.trim());
        return R.ok(out);
    }

    /** 轨迹同步（模拟服务商接口推送节点，运营/联调用） */
    @PostMapping("/trace/sync/{waybillId}")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> syncTrace(@PathVariable Long waybillId,
                                            @RequestBody List<Map<String, Object>> nodes) {
        return R.ok("轨迹同步完成", waybillService.syncTrace(waybillId, nodes));
    }

    // ===== 商户端发货资料：可用服务商与运费模板（仅启用项；精简 VO 不含接口地址/联系人/结算等平台配置） =====

    /** 发货选项：服务商（仅发货选择所需字段） */
    public record ProviderOption(Long providerId, String providerName, String providerCode, String apiType) {}

    /** 发货选项：运费模板（仅发货选择所需字段） */
    public record TemplateOption(Long templateId, String templateName, Long providerId) {}

    /** 可用物流服务商列表（发货弹窗选择用；需商户管理权限） */
    @GetMapping("/providers")
    @RequirePerm("merchant:manage")
    public R<List<ProviderOption>> providers() {
        return R.ok(waybillService.enabledProviders().stream()
                .map(p -> new ProviderOption(p.getProviderId(), p.getProviderName(), p.getProviderCode(), p.getApiType()))
                .toList());
    }

    /** 可用运费模板列表（可按服务商过滤；需商户管理权限） */
    @GetMapping("/templates")
    @RequirePerm("merchant:manage")
    public R<List<TemplateOption>> templates(
            @RequestParam(required = false) Long providerId) {
        return R.ok(waybillService.enabledTemplates(providerId).stream()
                .map(t -> new TemplateOption(t.getTemplateId(), t.getTemplateName(), t.getProviderId()))
                .toList());
    }

    // ===== Phase 3A: AI 运力智能推荐与在途 SLA 控制塔大屏 =====

    /** AI 最优运力多目标智能比价推荐 */
    @PostMapping("/recommend-carriers")
    @RequirePerm("merchant:manage")
    public R<com.lanlink.shopping.service.LogisticsCarrierRecommenderService.RecommendResult> recommendCarriers(
            @RequestBody com.lanlink.shopping.service.LogisticsCarrierRecommenderService.RecommendRequest req) {
        return R.ok(carrierRecommenderService.recommend(req));
    }

    /** 平台/商户端物流全景 SLA 运营控制塔大屏数据聚合（商户与管理员可见，游客拒绝） */
    @GetMapping("/dashboard/sla-overview")
    @RequirePerm("merchant:manage")
    public R<com.lanlink.shopping.service.LogisticsSlaWatchdogService.SlaDashboardOverview> slaOverview() {
        return R.ok(slaWatchdogService.getSlaDashboardOverview());
    }

    /** 手动触发在途停滞 SLA 智能看门狗巡检（运营/调度用） */
    @PostMapping("/watchdog/run-inspection")
    @RequirePerm("admin:all")
    public R<com.lanlink.shopping.service.LogisticsSlaWatchdogService.InspectionReport> runInspection() {
        return R.ok("巡检完成", slaWatchdogService.runInspection());
    }

    /** 一键官方加急催办 */
    @PostMapping("/watchdog/expedite/{waybillId}")
    @RequirePerm("merchant:manage")
    public R<String> expediteWaybill(@PathVariable Long waybillId,
                                    @RequestParam(required = false, defaultValue = "商户一键催派") String note) {
        slaWatchdogService.expediteWaybill(waybillId, note);
        return R.ok("加急催单已通知承运专席");
    }
}
