package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.MerchantApplyDTO;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 商户入驻与筛选 + 运营审核
 */
@RestController
@RequestMapping("/merchant")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
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

    /** 运营端: 商户审核列表 (需 admin 角色, 由拦截器鉴权) */
    @GetMapping("/admin/list")
    public R<List<Merchant>> adminList(@RequestParam(required = false) Integer reviewStatus) {
        return R.ok(merchantService.listForAdmin(reviewStatus));
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
