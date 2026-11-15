package com.lanlink.shopping.module.marketingimage.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.marketingimage.MarketingImageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 营销图生成接口（商家端 + 运营后台）。
 *
 * <p>商家端必须带 merchant:manage 权限；后台路径由 AuthInterceptor 统一校验 role_id=3，
 * 因此这里不再重复标注 @RequirePerm，避免类级/方法级权限语义互相覆盖。
 * 归属判定一律以服务端登录态取 userId，绝不信前端传入的 ownerId。
 */
@RestController
public class MarketingImageController {

    private static final String OWNER_MERCHANT = "MERCHANT";
    private static final String OWNER_ADMIN = "ADMIN";

    private final MarketingImageService service;

    public MarketingImageController(MarketingImageService service) {
        this.service = service;
    }

    // ==================== 商家端 ====================

    @PostMapping("/merchant/marketing-image/tasks")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> createForMerchant(@RequestBody(required = false) SubmitRequest body,
                                                    HttpServletRequest request) {
        SubmitRequest req = body == null ? new SubmitRequest() : body;
        return R.ok("已提交生成", service.submit(OWNER_MERCHANT, UserContext.currentUserId(request),
                req.getPrompt(), req.getNegativePrompt(), req.getSize(), req.getPro(), req.getMode()));
    }

    @GetMapping("/merchant/marketing-image/tasks")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> listForMerchant(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size,
                                                  HttpServletRequest request) {
        return R.ok(service.list(OWNER_MERCHANT, UserContext.currentUserId(request), page, size));
    }

    @GetMapping("/merchant/marketing-image/tasks/{taskNo}")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> detailForMerchant(@PathVariable String taskNo,
                                                    HttpServletRequest request) {
        return R.ok(service.detail(OWNER_MERCHANT, UserContext.currentUserId(request), taskNo));
    }

    // ==================== 运营后台 ====================

    @PostMapping("/admin/marketing-image/tasks")
    public R<Map<String, Object>> createForAdmin(@RequestBody(required = false) SubmitRequest body,
                                                 HttpServletRequest request) {
        SubmitRequest req = body == null ? new SubmitRequest() : body;
        return R.ok("已提交生成", service.submit(OWNER_ADMIN, UserContext.currentUserId(request),
                req.getPrompt(), req.getNegativePrompt(), req.getSize(), req.getPro(), req.getMode()));
    }

    @GetMapping("/admin/marketing-image/tasks")
    public R<Map<String, Object>> listForAdmin(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        // ownerType=null 表示不按归属过滤：管理员可查看全部商家的任务
        return R.ok(service.list(null, null, page, size));
    }

    @GetMapping("/admin/marketing-image/tasks/{taskNo}")
    public R<Map<String, Object>> detailForAdmin(@PathVariable String taskNo) {
        return R.ok(service.detail(null, null, taskNo));
    }

    @Data
    public static class SubmitRequest {
        private String prompt;
        private String negativePrompt;
        private String size;
        private Boolean pro;
        /** 可选模型：turbo / pro / mt；优先于 pro 布尔 */
        private String mode;
    }
}
