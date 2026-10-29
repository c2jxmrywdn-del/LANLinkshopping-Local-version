package com.lanlink.shopping.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 商城商品: 前台浏览(公开) + 商户发布(需登录且为商户, 进入平台审核)
 */
@RestController
@RequestMapping("/product")
public class ProductController {

    private final ProductService productService;
    private final com.lanlink.shopping.service.MerchantService merchantService;
    private final com.lanlink.shopping.module.recommend.service.PersonalizeService personalizeService;
    private final com.lanlink.shopping.module.search.ProductIndexJob productIndexJob;

    public ProductController(ProductService productService,
                             com.lanlink.shopping.service.MerchantService merchantService,
                             com.lanlink.shopping.module.recommend.service.PersonalizeService personalizeService,
                             com.lanlink.shopping.module.search.ProductIndexJob productIndexJob) {
        this.productService = productService;
        this.merchantService = merchantService;
        this.personalizeService = personalizeService;
        this.productIndexJob = productIndexJob;
    }

    /** 商品图上传目录：{user.dir}/uploads/products（WebConfig 已映射 /uploads/**） */
    private static final String PRODUCT_IMG_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "products";
    private static final long MAX_IMG_SIZE = 5 * 1024 * 1024;

    /**
     * 商城商品分页（公开）。personalization=true 且登录且 analytics 已授权时走个性化推流
     * （习惯类目打分 + 多样性打散）；其余情况原样自然排序——个性化降级在服务端闭环。
     */
    @GetMapping("/page")
    public R<Object> page(@RequestParam(defaultValue = "1") long current,
                          @RequestParam(defaultValue = "12") long size,
                          @RequestParam(required = false) String keyword,
                          @RequestParam(required = false) Long catId,
                          @RequestParam(required = false) Long indId,
                          @RequestParam(required = false) String sort,
                          @RequestParam(defaultValue = "false") boolean personalization,
                          @RequestHeader(value = "Cookie", required = false) String cookie,
                          HttpServletRequest request) {
        // 语义搜索：有关键词且无结构化筛选时走 jieba+向量双路 RRF（内部含开关与降级链）
        IPage<Product> page = hasText(keyword)
                ? productService.searchPage(current, size, keyword, catId, indId, sort)
                : productService.page(current, size, keyword, catId, indId, sort);
        // 仅综合态（无搜索/筛选/显式排序）走个性化推流
        boolean eligible = personalization
                && !hasText(keyword) && catId == null && indId == null && !hasText(sort);
        IPage<Product> personalized = eligible
                ? personalizeService.page(current, size, UserContext.currentUserId(request), analyticsCookie(cookie),
                        keyword, catId, indId, sort)
                : null;
        if (personalized != null) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("records", personalized.getRecords());
            out.put("total", personalized.getTotal());
            out.put("current", personalized.getCurrent());
            out.put("size", personalized.getSize());
            out.put("pages", personalized.getPages());
            out.put("personalized", true);
            return R.ok(out);
        }
        return R.ok((Object) page);
    }

    /** 管理端：手动触发商品向量索引重建（商品发布/批量导入后可立即生效） */
    @PostMapping("/admin/search/reindex")
    public R<Map<String, Object>> reindex() {
        return R.ok(new java.util.LinkedHashMap<>(productIndexJob.reindex()));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String analyticsCookie(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split("; ")) {
            if (part.startsWith("ll_analytics=")) return part.substring("ll_analytics=".length());
        }
        return null;
    }

    /** 前台详情：仅可售(审核通过)商品可见 */
    @GetMapping("/detail/{id}")
    public R<Product> detail(@PathVariable Long id) {
        return R.ok(productService.detailForPublic(id));
    }

    /** 商户发布商品：发布后自动进入「待审核」，审核通过才可售（需 product:publish 权限） */
    @PostMapping("/publish")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Product> publish(@Valid @RequestBody Product product, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        var merchant = merchantService.getByUser(userId);
        if (merchant == null) return R.fail("请先完成商户入驻");
        if (merchant.getReviewStatus() == null || merchant.getReviewStatus() != 1) {
            return R.fail("商户审核未通过，暂不能上架商品");
        }
        return R.ok("已提交，等待平台审核", productService.publish(merchant.getMerId(), product));
    }

    /** 商户自己的商品列表（含审核状态，实时同步；可按关键词/审核状态/可售状态过滤） */
    @GetMapping("/my/list")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<IPage<Product>> myList(@RequestParam(defaultValue = "1") long current,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer reviewStatus,
                                    @RequestParam(required = false) Integer status,
                                    HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        var merchant = merchantService.getByUser(userId);
        if (merchant == null) return R.fail("请先完成商户入驻");
        return R.ok(productService.myPage(merchant.getMerId(), current, size, keyword, reviewStatus, status));
    }

    /** 商户自己的商品详情（编辑回显；归属校验） */
    @GetMapping("/my/detail/{prodId}")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Product> myDetail(@PathVariable Long prodId, HttpServletRequest request) {
        var merchant = mustApprovedMerchant(UserContext.currentUserId(request));
        return R.ok(productService.myDetail(merchant.getMerId(), prodId));
    }

    /**
     * 商户编辑自己的商品：仅调库存即时生效；
     * 修改内容字段（标题/价格/分类/主图/详情等）会重新送审（待审核且不可售）。
     */
    @PostMapping("/my/update/{prodId}")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Product> myUpdate(@PathVariable Long prodId, @RequestBody Product patch,
                               HttpServletRequest request) {
        var merchant = mustApprovedMerchant(UserContext.currentUserId(request));
        return R.ok("保存成功", productService.myUpdate(merchant.getMerId(), prodId, patch));
    }

    /** 商户自主上架/下架（status 1上架 0下架；仅审核通过的商品可操作） */
    @PostMapping("/my/status/{prodId}")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<Product> myStatus(@PathVariable Long prodId, @RequestParam Integer status,
                               HttpServletRequest request) {
        var merchant = mustApprovedMerchant(UserContext.currentUserId(request));
        return R.ok(status == 1 ? "商品已上架" : "商品已下架",
                productService.mySetStatus(merchant.getMerId(), prodId, status));
    }

    /** 已通过审核的入驻商户（商品管理操作前置条件） */
    private com.lanlink.shopping.entity.Merchant mustApprovedMerchant(Long userId) {
        var merchant = merchantService.getByUser(userId);
        if (merchant == null) throw new BusinessException("请先完成商户入驻");
        if (merchant.getReviewStatus() == null || merchant.getReviewStatus() != 1) {
            throw new BusinessException("商户审核未通过，暂不能管理商品");
        }
        return merchant;
    }

    /** 商品主图上传：JPG/PNG/WebP，魔数校验防伪造，返回 URL 供 publish 引用 */
    @PostMapping("/image")
    public R<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) throws IOException {
        UserContext.currentUserId(request); // 需登录
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择图片文件");
        if (file.getSize() > MAX_IMG_SIZE) throw new IllegalArgumentException("图片大小不能超过 5MB");
        String kind;
        try (InputStream in = file.getInputStream()) {
            byte[] h = in.readNBytes(12);
            if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) kind = "jpg";
            else if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) kind = "png";
            else if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') kind = "webp";
            else throw new IllegalArgumentException("仅支持 JPG/PNG/WebP 图片");
        }
        BufferedImage img = null;
        try {
            img = ImageIO.read(file.getInputStream());
        } catch (IOException e) {
            throw new IllegalArgumentException("图片解析失败，请重新上传");
        }
        if (img != null) {
            if (img.getWidth() < 100 || img.getHeight() < 100) {
                throw new IllegalArgumentException("图片尺寸过小（需≥100×100）");
            }
        } else if (!"webp".equals(kind)) {
            throw new IllegalArgumentException("图片解析失败，请重新上传");
        }
        File dir = new File(PRODUCT_IMG_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new RuntimeException("上传目录创建失败");
        String name = "prod_" + System.currentTimeMillis() + "." + kind;
        file.transferTo(new File(dir, name).getAbsoluteFile());
        return R.ok(Map.of("url", "/api/uploads/products/" + name));
    }

    /** 管理后台: 全量商品列表(含已下架), 支持多维筛选 */
    @GetMapping("/admin/list")
    public R<IPage<Product>> adminList(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) Integer reviewStatus,
                                       @RequestParam(required = false) Long indId,
                                       @RequestParam(required = false) Long catId,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(productService.adminPage(current, size, status, reviewStatus, indId, catId, keyword));
    }

    /** 管理后台: 商品审核统计看板 (总数/待审核/已通过/已驳回/已下架/通过率) */
    @GetMapping("/admin/stats")
    public R<Map<String, Object>> adminStats() {
        return R.ok(productService.adminStats());
    }

    /** 管理后台: 商品审核全景详情与合规初筛校验报告 */
    @GetMapping("/admin/detail/{prodId}")
    public R<Map<String, Object>> adminDetail(@PathVariable Long prodId) {
        return R.ok(productService.adminDetail(prodId));
    }

    /** 管理后台: 批量审核商品（批量通过/批量驳回） */
    @PostMapping("/admin/batch-review")
    public R<Map<String, Object>> adminBatchReview(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long adminUserId = UserContext.currentUserId(request);
        @SuppressWarnings("unchecked")
        java.util.List<Integer> rawIds = (java.util.List<Integer>) body.get("prodIds");
        if (rawIds == null) throw new IllegalArgumentException("请选择商品");
        java.util.List<Long> prodIds = rawIds.stream().map(Integer::longValue).toList();
        Integer reviewStatus = (Integer) body.get("reviewStatus");
        String reason = (String) body.get("reason");
        return R.ok(productService.batchReview(prodIds, reviewStatus, reason, adminUserId, request));
    }

    /** 管理后台: 获取商品审核流转审计历史 */
    @GetMapping("/admin/audit-logs/{prodId}")
    public R<java.util.List<com.lanlink.shopping.entity.AuditLog>> adminAuditLogs(@PathVariable Long prodId) {
        return R.ok(productService.auditLogs(prodId));
    }

    /** 管理后台: 上架/下架 (status 1上架 0下架) */
    @PostMapping("/admin/status/{prodId}")
    public R<Product> adminStatus(@PathVariable Long prodId, @RequestParam Integer status) {
        return R.ok(productService.setStatus(prodId, status));
    }

    /** 管理后台: 商品审核（通过→自动可售；驳回→记录原因并保持不可售） */
    @PostMapping("/admin/review/{prodId}")
    public R<Product> adminReview(@PathVariable Long prodId,
                                  @RequestParam Integer reviewStatus,
                                  @RequestParam(required = false) String reason) {
        return R.ok(productService.review(prodId, reviewStatus, reason));
    }
}
