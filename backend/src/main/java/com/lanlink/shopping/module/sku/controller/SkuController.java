package com.lanlink.shopping.module.sku.controller;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.sku.dto.*;
import com.lanlink.shopping.module.sku.service.SkuService;
import com.lanlink.shopping.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 商户 SKU 多规格树读写（挂在 /product/my/sku 下，与 /product/my/* 兄弟端点同门禁）。
 *
 * <p>三重收口对齐 ProductController /my/* 先例：① {@link RequirePerm}("product:publish")
 * ——匿名 401、买家/VIP 403（由 PermInterceptor 既有链路执行）；② 方法内
 * mustApprovedMerchant——未入驻/未过审商户直接拒绝；③ 归属校验由 SkuService.requireOwned
 * 兜底（非自有商品 403 / 不存在 404）。
 *
 * <p>安全双端：WebConfig 已为 /product/my/sku/** 注册 CsrfInterceptor 与
 * AuthRateLimitInterceptor（POLICIES 键 /product/my/sku/*），前端 csrfPolicy.js 同步镜像。
 */
@RestController @RequestMapping("/product/my/sku")
public class SkuController {
    private final SkuService skuService; private final MerchantService merchantService;
    public SkuController(SkuService s,MerchantService m){ skuService=s; merchantService=m; }

    @GetMapping("/{prodId}") @RequirePerm("product:publish")
    public R<SkuTreeView> load(@PathVariable Long prodId,HttpServletRequest req){
        var m=mustApprovedMerchant(UserContext.currentUserId(req));
        return R.ok(skuService.load(m.getMerId(),prodId));
    }
    @PostMapping("/{prodId}/save") @RequirePerm("product:publish")
    public R<SkuTreeView> save(@PathVariable Long prodId,@Valid @RequestBody SkuTreeSaveRequest body,HttpServletRequest req){
        var m=mustApprovedMerchant(UserContext.currentUserId(req));
        com.lanlink.shopping.module.merchantexit.service.MerchantExitGuard.requireWritable(m);
        return R.ok("保存成功",skuService.save(m.getMerId(),prodId,body));
    }
    private com.lanlink.shopping.entity.Merchant mustApprovedMerchant(Long userId){
        var m=merchantService.getByUser(userId);
        if(m==null) throw new BusinessException("请先完成商户入驻");
        if(m.getReviewStatus()==null||m.getReviewStatus()!=1) throw new BusinessException("商户审核未通过，暂不能管理商品");
        return m;
    }
}
