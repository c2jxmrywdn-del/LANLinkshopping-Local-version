package com.lanlink.shopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.MerchantApplyDTO;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.service.LicenseImageWatermark;
import com.lanlink.shopping.service.LicenseViewGateService;
import com.lanlink.shopping.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 商户入驻与筛选 + 运营审核
 */
@RestController
@RequestMapping("/merchant")
public class MerchantController {

    private final MerchantService merchantService;
    private final LicenseViewGateService licenseViewGateService;
    private final ObjectMapper json = new ObjectMapper();

    public MerchantController(MerchantService merchantService, LicenseViewGateService licenseViewGateService) {
        this.merchantService = merchantService;
        this.licenseViewGateService = licenseViewGateService;
    }

    /** 营业执照上传目录：{user.dir}/uploads/licenses（WebConfig 已映射 /uploads/**） */
    private static final String LICENSE_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "licenses";
    /** 税务证明上传目录 */
    private static final String TAX_PROOF_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "tax-proofs";
    /** 特殊经营许可证上传目录 */
    private static final String SPECIAL_PERMIT_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "special-permits";
    private static final long MAX_SIZE = 5 * 1024 * 1024;
    /** 入驻申请材料上传上限：10MB（工商信息/纳税记录/特殊经营许可） */
    private static final long APPLY_MAX_SIZE = 10 * 1024 * 1024;
    /** 营业执照最小边长（像素）：保证执照信息完整可辨 */
    private static final int MIN_LICENSE_EDGE = 400;
    /**
     * 人机验证未通过的统一提示。
     *
     * <p>不区分「未携带 / 已过期 / 已被使用 / 账号商户不匹配」——对外只给一句话，
     * 避免把票据状态差异暴露成可探测的 oracle。
     */
    private static final String GATE_REQUIRED_MESSAGE = "请先完成人机验证";

    /** 提交入驻申请(自动执行筛选机制) */
    @PostMapping("/apply")
    public R<Merchant> apply(@Valid @RequestBody MerchantApplyDTO dto, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("入驻申请已提交", merchantService.apply(userId, null, dto));
    }

    /**
     * 入驻申请材料上传（申请阶段专用，仅需登录）：工商信息/纳税记录/特殊经营许可在提交申请前先行上传，
     * 返回 { url } 随申请表单一并提交。与已入驻商户的 /license、/tax-proof（merchant:manage）权限隔离。
     */
    @PostMapping("/apply-upload")
    public R<Map<String, String>> applyUpload(@RequestParam("file") MultipartFile file,
                                              @RequestParam String kind,
                                              HttpServletRequest request) throws IOException {
        Long userId = UserContext.currentUserId(request);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择文件");
        if (file.getSize() > APPLY_MAX_SIZE) throw new IllegalArgumentException("文件大小不能超过 10MB");
        String detected = sniff(file);
        boolean license = "license".equals(kind);
        boolean specialPermit = "specialPermit".equalsIgnoreCase(kind) || "special-permit".equalsIgnoreCase(kind);
        if (license && !"jpg".equals(detected) && !"png".equals(detected)) {
            throw new IllegalArgumentException("工商信息（营业执照）仅支持 JPG/PNG 格式");
        }
        if (!license && !"pdf".equals(detected) && !"jpg".equals(detected) && !"png".equals(detected)) {
            throw new IllegalArgumentException(specialPermit ? "特殊经营许可证仅支持 JPG/PNG/PDF 格式" : "纳税记录仅支持 JPG/PNG/PDF 格式");
        }
        String targetDir = license ? LICENSE_DIR : (specialPermit ? SPECIAL_PERMIT_DIR : TAX_PROOF_DIR);
        String targetFolder = license ? "licenses" : (specialPermit ? "special-permits" : "tax-proofs");
        String url = save(file, targetDir, targetFolder, userId, detected);
        return R.ok("上传成功", Map.of("url", url));
    }

    /** 我的商户信息 */
    @GetMapping("/my")
    public R<Merchant> my(HttpServletRequest request) {
        return R.ok(merchantService.getByUser(UserContext.currentUserId(request)));
    }

    /**
     * 公开店铺卡片信息（店铺首页 / 店铺列表 / 搜索结果里的店铺条目）。
     *
     * <p>匿名可访问（AuthInterceptor 只拦非 GET），只返回公开字段：
     * 店铺名、企业名、是否「平台特邀商户」。<b>不含</b>法人/银行/联系方式等敏感数据。
     * `platformInvited` 由 {@code PlatformInvitedBadge.visible} 统一裁决，
     * 未标记 / 未过审 / 已关店的商户返回 null，前端不渲染标识。
     *
     * @param merIds 商户 ID 列表（逗号分隔，最多 100 个）
     */
    @GetMapping("/shops")
    public R<java.util.List<java.util.Map<String, Object>>> publicShops(@RequestParam String merIds) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String part : merIds.split(",")) {
            String s = part == null ? "" : part.trim();
            if (s.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // 非法 ID 直接跳过，不因单个脏参数让整批失败
            }
            if (ids.size() >= 100) break;
        }
        java.util.Map<Long, java.util.Map<String, Object>> cards = merchantService.publicShopCards(ids);
        return R.ok(new java.util.ArrayList<>(cards.values()));
    }

    /** 营业执照上传（商户专属）：JPG/PNG 合规校验，落盘并同步写入商户档案 license_url */
    @PostMapping("/license")
    @com.lanlink.shopping.integration.security.RequirePerm("merchant:manage")
    public R<Map<String, String>> uploadLicense(@RequestParam("file") MultipartFile file,
                                                HttpServletRequest request) throws IOException {
        Long userId = UserContext.currentUserId(request);
        String url = saveImage(file, LICENSE_DIR, "licenses", userId);
        merchantService.persistLicense(userId, url);
        return R.ok("营业执照已更新", Map.of("url", url));
    }

    /**
     * 税务缴纳证明上传（商户专属）：PDF/JPG/PNG，落盘并追加写入商户档案 tax_proof_urls（最多 3 份）。
     * period：所属期（YYYY-MM），可选。传入时会写入文件名，
     * 使月度上报可精确归属到「上一自然月」，避免仅凭上传时间推断造成跨月歧义。
     */
    @PostMapping("/tax-proof")
    @com.lanlink.shopping.integration.security.RequirePerm("merchant:manage")
    public R<Map<String, String>> uploadTaxProof(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) String period,
                                                 HttpServletRequest request) throws IOException {
        Long userId = UserContext.currentUserId(request);
        String url = saveTaxProof(file, TAX_PROOF_DIR, "tax-proofs", userId, period);
        merchantService.persistTaxProof(userId, url);
        return R.ok("税务证明已上传", Map.of("url", url));
    }

    /** 税务登记号查询（商户专属）：返回近 3 个月缴纳记录，并将登记号绑定至商户档案 */
    @GetMapping("/tax-query")
    @com.lanlink.shopping.integration.security.RequirePerm("merchant:manage")
    public R<Map<String, Object>> taxQuery(@RequestParam String taxRegNo, HttpServletRequest request) {
        Map<String, Object> out = merchantService.queryTaxRecords(taxRegNo);
        merchantService.persistTaxRegNo(UserContext.currentUserId(request), taxRegNo);
        return R.ok(out);
    }

    /**
     * 运营端: 商户审核列表 (需 admin 角色, 由拦截器鉴权)。
     *
     * <p>返回脱敏投影而非实体：实体带加密 typeHandler，直接序列化会把法人姓名/身份证/
     * 对公账号/联系电话等明文下发到浏览器，绕过敏感信息查看门禁。
     */
    @GetMapping("/admin/list")
    public R<List<com.lanlink.shopping.vo.MerchantAdminListItemVO>> adminList(
            @RequestParam(required = false) Integer reviewStatus) {
        return R.ok(merchantService.listForAdmin(reviewStatus).stream()
                .map(com.lanlink.shopping.vo.MerchantAdminListItemVO::from)
                .toList());
    }

    /** 运营端: 商户统计看板 (商户总数、待审核、已通过、已驳回、初筛率等) */
    @GetMapping("/admin/stats")
    public R<Map<String, Object>> adminStats() {
        return R.ok(merchantService.stats());
    }

    /** 运营端: 商户详情（工商信息/资质/证照完整度，资料维护与资质验证视图） */
    @GetMapping("/admin/detail/{merId}")
    public R<Map<String, Object>> adminDetail(@PathVariable Long merId) {
        return R.ok(merchantService.adminDetail(merId));
    }

    /** 运营端: 安全调阅商户敏感明文（法人身份证、对公银行账号，需操作验权并留存审计日志） */
    @GetMapping("/admin/sensitive/{merId}")
    public R<Map<String, Object>> adminSensitive(@PathVariable Long merId, HttpServletRequest request) {
        Long adminUserId = UserContext.currentUserId(request);
        return R.ok(merchantService.getAdminSensitive(merId, adminUserId, request));
    }

    /** 运营端: 审核通过/驳回 */
    @PostMapping("/admin/review/{merId}")
    public R<Merchant> review(@PathVariable Long merId,
                              @RequestParam Integer reviewStatus,
                              @RequestParam(required = false) String reason) {
        return R.ok(merchantService.review(merId, reviewStatus, reason));
    }

    /** 运营端：手动标记或取消「平台特邀商户」。未过审或店铺已关闭时前台不展示标识。 */
    @PostMapping("/admin/platform-invited/{merId}")
    public R<Merchant> markPlatformInvited(@PathVariable Long merId,
                                           @RequestParam boolean enabled) {
        return R.ok(enabled ? "已标记为平台特邀商户" : "已取消平台特邀标记",
                merchantService.markPlatformInvited(merId, enabled));
    }

    /** 运营端: 批量审核商户（批量通过/批量驳回） */
    @PostMapping("/admin/batch-review")
    public R<Map<String, Object>> batchReview(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long adminUserId = UserContext.currentUserId(request);
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("merIds");
        if (rawIds == null) throw new IllegalArgumentException("请选择商户");
        List<Long> merIds = rawIds.stream().map(Integer::longValue).toList();
        Integer reviewStatus = (Integer) body.get("reviewStatus");
        String reason = (String) body.get("reason");
        return R.ok(merchantService.batchReview(merIds, reviewStatus, reason, adminUserId, request));
    }

    /** 运营端: 获取商户审核流转审计历史 */
    @GetMapping("/admin/audit-logs/{merId}")
    public R<List<com.lanlink.shopping.entity.AuditLog>> adminAuditLogs(@PathVariable Long merId) {
        return R.ok(merchantService.auditLogs(merId));
    }

    // ===== 营业执照查看：人机验证门 + 水印注入 =====

    /**
     * 运营端: 获取营业执照查看图形验证码（PNG base64，点击可刷新）。
     * 验证码与商户绑定：校验时票据只签给同一商户。
     */
    @GetMapping("/admin/license-captcha")
    public R<Map<String, String>> licenseCaptcha(@RequestParam Long merId) {
        LicenseViewGateService.CaptchaIssue issue = licenseViewGateService.newCaptcha(merId);
        return R.ok(Map.of("captchaId", issue.captchaId(), "image", issue.imageBase64()));
    }

    /** 运营端: 校验验证码并签发短时效查看票据（5 分钟、绑定当前运营账号与商户、单次使用） */
    @PostMapping("/admin/license-verify")
    public R<Map<String, String>> licenseVerify(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Long merId = body.get("merId") instanceof Number n ? n.longValue() : null;
        String captchaId = body.get("captchaId") instanceof String s ? s : null;
        String captchaCode = body.get("captchaCode") instanceof String s ? s : null;
        String ticket = licenseViewGateService.issueTicket(userId, merId, captchaId, captchaCode);
        return R.ok("验证通过", Map.of("ticket", ticket));
    }

    /**
     * 运营端: 含水印营业执照（PNG 流式输出，no-store 禁缓存）。
     * admin 角色门槛由 AuthInterceptor 的 /admin/ 规则维持；票据无效/过期/重放/错商户/错账号一律 HTTP 403。
     */
    @GetMapping("/admin/license-watermarked/{merId}")
    public ResponseEntity<byte[]> licenseWatermarked(@PathVariable Long merId,
                                                     @RequestParam(required = false) String ticket,
                                                     HttpServletRequest request) {
        User admin = UserContext.current(request);
        if (admin == null) {
            return gateError(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        try {
            licenseViewGateService.consumeTicket(admin.getUserId(), merId, ticket);
        } catch (BusinessException e) {
            // 票据类拒绝落真实 HTTP 403；对外统一提示，不暴露票据状态差异
            return gateError(HttpStatus.FORBIDDEN, GATE_REQUIRED_MESSAGE);
        }
        try {
            return buildWatermarkedResponse(merchantService.requireMerchant(merId), admin.getUserId());
        } catch (BusinessException e) {
            return gateError(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * 水印响应构建（运营端与商户端共用同一条输出链路）。
     *
     * <p>保证「所有对外输出的营业执照」都经过同一个函数：
     * 读取源文件 → 内存叠加水印 → 输出 PNG。源文件只读不写，
     * 因此不存在"某条路径漏加水印"或"水印覆盖源文件"的可能。
     */
    private ResponseEntity<byte[]> buildWatermarkedResponse(Merchant m, Long viewerUserId) {
        if (m == null || m.getLicenseUrl() == null || m.getLicenseUrl().isBlank()) {
            return gateError(HttpStatus.NOT_FOUND, "商户暂未上传营业执照原件");
        }
        byte[] original;
        String merchantName;
        try {
            merchantName = merchantService.merchantDisplayName(m);
            // 只读：Files.readAllBytes 不会修改源文件
            original = Files.readAllBytes(resolveLicenseFile(m.getLicenseUrl()).toPath());
        } catch (BusinessException e) {
            return gateError(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IOException e) {
            return gateError(HttpStatus.NOT_FOUND, "营业执照文件不存在或已清理");
        }
        byte[] png;
        try {
            png = LicenseImageWatermark.applyPng(original, watermarkLinesFor(merchantName, viewerUserId));
        } catch (BusinessException e) {
            // 商户企业名缺失：拒绝出图，绝不生成缺主体/伪造主体的水印
            return gateError(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IOException e) {
            return gateError(HttpStatus.UNPROCESSABLE_ENTITY, "营业执照图片解析失败");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    /**
     * 生成水印文案（单测断言点）。
     *
     * <p>第一行锚定<b>查看方</b>（截图外流可定位到账号），
     * 第二行锚定<b>商户企业名</b>——「最终解释权」的唯一合法主体，
     * 与营业执照上的公司名逐字一致，不得被查看方/平台名/占位词替换。
     *
     * @param merchantCompanyName 商户企业名；缺失时由 {@link LicenseViewGateService} 抛 422
     */
    String[] watermarkLinesFor(String merchantCompanyName, Long viewerUserId) {
        return LicenseViewGateService.buildWatermarkLines(
                merchantService.adminDisplayName(viewerUserId), merchantCompanyName);
    }

    // ===== 商户端自助查看：同样必须过人机验证 + 服务端水印 =====

    /**
     * 商户端: 获取本人营业执照查看的图形验证码。
     *
     * <p>只允许查看**自己**的执照：merId 由服务端按当前登录账号解析，
     * 不接受前端传入，避免商户 A 拿到商户 B 的验证码。
     */
    @GetMapping("/license-captcha")
    @com.lanlink.shopping.integration.security.RequirePerm("merchant:manage")
    public R<Map<String, String>> myLicenseCaptcha(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Merchant mine = merchantService.getByUser(userId);
        if (mine == null) {
            throw new BusinessException(404, "您还没有商户档案");
        }
        LicenseViewGateService.CaptchaIssue issue = licenseViewGateService.newCaptcha(mine.getMerId());
        return R.ok(Map.of("captchaId", issue.captchaId(), "image", issue.imageBase64()));
    }

    /** 商户端: 校验验证码并签发短时效查看票据（绑定当前商户账号与本人商户） */
    @PostMapping("/license-verify")
    @com.lanlink.shopping.integration.security.RequirePerm("merchant:manage")
    public R<Map<String, String>> myLicenseVerify(@RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Merchant mine = merchantService.getByUser(userId);
        if (mine == null) {
            throw new BusinessException(404, "您还没有商户档案");
        }
        String captchaId = body.get("captchaId") instanceof String s ? s : null;
        String captchaCode = body.get("captchaCode") instanceof String s ? s : null;
        // 关键：merId 取服务端解析结果，忽略前端传入值
        String ticket = licenseViewGateService.issueTicket(userId, mine.getMerId(), captchaId, captchaCode);
        return R.ok("验证通过", Map.of("ticket", ticket));
    }

    /**
     * 商户端: 本人营业执照（含水印 PNG，no-store 禁缓存）。
     *
     * <p>与运营端同一条水印链路：票据无效/过期/重放/错商户/错账号一律 HTTP 403，
     * 且返回统一提示「请先完成人机验证」，不泄露具体失败原因。
     */
    @GetMapping("/license-watermarked")
    @com.lanlink.shopping.integration.security.RequirePerm("merchant:manage")
    public ResponseEntity<byte[]> myLicenseWatermarked(@RequestParam(required = false) String ticket,
                                                       HttpServletRequest request) {
        User me = UserContext.current(request);
        if (me == null) {
            return gateError(HttpStatus.UNAUTHORIZED, GATE_REQUIRED_MESSAGE);
        }
        Merchant mine = merchantService.getByUser(me.getUserId());
        if (mine == null) {
            return gateError(HttpStatus.NOT_FOUND, "您还没有商户档案");
        }
        try {
            licenseViewGateService.consumeTicket(me.getUserId(), mine.getMerId(), ticket);
        } catch (BusinessException e) {
            // 统一提示：不区分"未带/过期/重放/错商户"，避免成为探测面
            return gateError(HttpStatus.FORBIDDEN, GATE_REQUIRED_MESSAGE);
        }
        return buildWatermarkedResponse(mine, me.getUserId());
    }

    /**
     * 运营端: 特殊经营许可证原件（受控读取）。
     *
     * <p>证照目录已从 `/uploads/**` 静态映射中剥离（防任意登录用户越权读取），
     * 因此必须经本端点读取：admin 门槛由 AuthInterceptor 的 `/admin/` 规则保证，
     * 这里再做一次路径穿越校验（只允许 uploads/special-permits 下的文件）。
     */
    @GetMapping("/admin/special-permit/{merId}")
    public ResponseEntity<byte[]> adminSpecialPermit(@PathVariable Long merId) {
        Merchant m;
        try {
            m = merchantService.requireMerchant(merId);
        } catch (BusinessException e) {
            return gateError(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (m.getSpecialPermitUrl() == null || m.getSpecialPermitUrl().isBlank()) {
            return gateError(HttpStatus.NOT_FOUND, "该商户未上传特殊经营许可证");
        }
        File file;
        try {
            file = resolveUploadFile(m.getSpecialPermitUrl(), "special-permits");
        } catch (BusinessException e) {
            return gateError(HttpStatus.NOT_FOUND, e.getMessage());
        }
        try {
            byte[] body = Files.readAllBytes(file.toPath());
            MediaType type = file.getName().toLowerCase().endsWith(".pdf")
                    ? MediaType.APPLICATION_PDF : MediaType.IMAGE_PNG;
            return ResponseEntity.ok()
                    .contentType(type)
                    .cacheControl(CacheControl.noStore())
                    .body(body);
        } catch (IOException e) {
            return gateError(HttpStatus.NOT_FOUND, "证件文件不存在或已清理");
        }
    }

    /** 网关错误统一以 R 结构输出真实 HTTP 状态码（<img> 侧无法读 body，状态码是唯一信号） */
    private ResponseEntity<byte[]> gateError(HttpStatus status, String message) {
        try {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsBytes(R.fail(status.value(), message)));
        } catch (IOException ex) {
            return ResponseEntity.status(status).build();
        }
    }

    /**
     * license_url 落盘文件解析：规范形态 /api/uploads/licenses/{name}（context-path=/api，
     * 历史数据存在缺 /api 前缀形态）；归一化后必须仍位于 uploads 根内，阻断路径穿越。
     */
    private File resolveLicenseFile(String licenseUrl) {
        return resolveUploadFile(licenseUrl, "licenses");
    }

    /**
     * 受控上传文件解析：只允许 `uploads/{subDir}/` 下的文件，归一化后必须仍在根内。
     *
     * @param url    形如 /api/uploads/special-permits/xxx.png（兼容缺 /api 前缀的历史数据）
     * @param subDir 允许的二级目录（licenses / special-permits）
     */
    private File resolveUploadFile(String url, String subDir) {
        if (url == null || url.isBlank()) throw new BusinessException("证件地址为空");
        String path = url.trim();
        if (path.startsWith("/api/")) {
            path = path.substring("/api".length());
        }
        String expectedPrefix = "/uploads/" + subDir + "/";
        if (!path.startsWith(expectedPrefix)) {
            throw new BusinessException("证件地址不合规");
        }
        Path root = Paths.get(System.getProperty("user.dir"), "uploads").normalize();
        Path target = root.resolve(path.substring("/uploads/".length())).normalize();
        if (!target.startsWith(root.resolve(subDir))) {
            throw new BusinessException("证件地址不合规");
        }
        File file = target.toFile();
        if (!file.isFile() || !file.canRead()) {
            throw new BusinessException("证件文件不存在或已清理");
        }
        return file;
    }

    // ===== 上传文件落盘与合规校验 =====

    /** 读取文件头魔数，防伪造扩展名 */
    private static String sniff(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            byte[] h = in.readNBytes(8);
            if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "jpg";
            if (h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) return "png";
            if (h.length >= 4 && h[0] == 0x25 && h[1] == 0x50 && h[2] == 0x44 && h[3] == 0x46) return "pdf"; // %PDF
            return "unknown";
        }
    }

    private static void checkCommon(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择文件");
        if (file.getSize() > MAX_SIZE) throw new IllegalArgumentException("文件大小不能超过 5MB");
    }

    /** 营业执照：仅 JPG/PNG + 最小边长校验（信息完整可辨） */
    private String saveImage(MultipartFile file, String dir, String folder, Long userId) throws IOException {
        checkCommon(file);
        String kind = sniff(file);
        if (!"jpg".equals(kind) && !"png".equals(kind)) {
            throw new IllegalArgumentException("营业执照仅支持 JPG/PNG 格式图片");
        }
        BufferedImage img;
        try {
            img = ImageIO.read(file.getInputStream());
        } catch (IOException e) {
            throw new IllegalArgumentException("图片解析失败，请重新上传");
        }
        if (img == null || img.getWidth() < MIN_LICENSE_EDGE || img.getHeight() < MIN_LICENSE_EDGE) {
            throw new IllegalArgumentException("图片尺寸过小（需≥" + MIN_LICENSE_EDGE + "×" + MIN_LICENSE_EDGE
                    + "），请上传完整清晰的营业执照");
        }
        return save(file, dir, folder, userId, kind);
    }

    /** 税务证明：PDF/JPG/PNG + 可选所属期（写入文件名，用于月度上报精确归属） */
    private String saveTaxProof(MultipartFile file, String dir, String folder, Long userId, String period) throws IOException {
        checkCommon(file);
        String kind = sniff(file);
        if (!"pdf".equals(kind) && !"jpg".equals(kind) && !"png".equals(kind)) {
            throw new IllegalArgumentException("税务证明仅支持 PDF/JPG/PNG 格式");
        }
        return save(file, dir, folder, userId, kind, period);
    }

    private String save(MultipartFile file, String dir, String folder, Long userId, String ext) throws IOException {
        return save(file, dir, folder, userId, ext, null);
    }

    private String save(MultipartFile file, String dir, String folder, Long userId, String ext, String period) throws IOException {
        File d = new File(dir);
        if (!d.exists() && !d.mkdirs()) throw new RuntimeException("上传目录创建失败");
        String suffix = (period != null && period.matches("\\d{4}-\\d{2}")) ? "_" + period : "";
        String name = userId + "_" + System.currentTimeMillis() + suffix + "." + ext;
        File dest = new File(d, name);
        file.transferTo(dest.getAbsoluteFile());
        return "/api/uploads/" + folder + "/" + name;
    }
}
