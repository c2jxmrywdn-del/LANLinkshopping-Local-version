package com.lanlink.shopping.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
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

    public ProductController(ProductService productService,
                             com.lanlink.shopping.service.MerchantService merchantService) {
        this.productService = productService;
        this.merchantService = merchantService;
    }

    /** 商品图上传目录：{user.dir}/uploads/products（WebConfig 已映射 /uploads/**） */
    private static final String PRODUCT_IMG_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator + "products";
    private static final long MAX_IMG_SIZE = 5 * 1024 * 1024;

    @GetMapping("/page")
    public R<IPage<Product>> page(@RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "12") long size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) Long catId,
                                  @RequestParam(required = false) Long indId,
                                  @RequestParam(required = false) String sort) {
        return R.ok(productService.page(current, size, keyword, catId, indId, sort));
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

    /** 商户自己的商品列表（含审核状态，实时同步；需 product:publish 权限） */
    @GetMapping("/my/list")
    @com.lanlink.shopping.integration.security.RequirePerm("product:publish")
    public R<IPage<Product>> myList(@RequestParam(defaultValue = "1") long current,
                                    @RequestParam(defaultValue = "10") long size,
                                    HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        var merchant = merchantService.getByUser(userId);
        if (merchant == null) return R.fail("请先完成商户入驻");
        return R.ok(productService.myPage(merchant.getMerId(), current, size));
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

    /** 管理后台: 全量商品列表(含已下架) */
    @GetMapping("/admin/list")
    public R<IPage<Product>> adminList(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(productService.adminPage(current, size, status, keyword));
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
