package com.lanlink.shopping.module.campaign.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.campaign.dto.CampaignUpsertRequest;
import com.lanlink.shopping.module.campaign.dto.CampaignView;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 活动管理 API（平台运营后台）
 *  - GET    /admin/campaign/page           全量活动分页（含未上架/已结束）
 *  - GET    /admin/campaign/{id}           详情 + 运营数据（领取/核销/参与/报名）
 *  - POST   /admin/campaign                创建活动
 *  - PUT    /admin/campaign/{id}           更新活动
 *  - POST   /admin/campaign/{id}/status    上架/下架
 *  - DELETE /admin/campaign/{id}           删除活动（有参与记录时禁止，应下架）
 *  - GET    /admin/campaign/audit/page     商户促销审核分页（reviewStatus 可空=全部）
 *  - POST   /admin/campaign/audit/{id}/review 商户促销审核（通过/驳回）
 */
@RestController
@RequestMapping("/admin/campaign")
@RequirePerm("admin:all")
public class CampaignAdminController {

    private final CampaignService campaignService;
    private final AuditService auditService;

    public CampaignAdminController(CampaignService campaignService, AuditService auditService) {
        this.campaignService = campaignService;
        this.auditService = auditService;
    }

    @GetMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(CampaignView.page(campaignService.adminPage(page, size, type, status, keyword)));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(campaignService.adminDetail(id));
    }

    @PostMapping
    public R<CampaignView> create(@RequestBody CampaignUpsertRequest campaign) {
        return R.ok("活动已创建", CampaignView.of(campaignService.adminCreate(campaign)));
    }

    @PutMapping("/{id}")
    public R<CampaignView> update(@PathVariable Long id, @RequestBody CampaignUpsertRequest campaign) {
        return R.ok("活动已更新", CampaignView.of(campaignService.adminUpdate(id, campaign)));
    }

    /** 上架(status=1)/下架(status=0) */
    @PostMapping("/{id}/status")
    public R<Void> toggleStatus(@PathVariable Long id, @RequestParam int status) {
        campaignService.adminToggle(id, status);
        return R.ok(status == 1 ? "活动已上架" : "活动已下架", null);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        campaignService.adminDelete(id);
        return R.ok("活动已删除", null);
    }

    // ==================== 商户促销审核 ====================

    /** 商户活动审核分页（reviewStatus 可空=全部：0待审 1通过 2驳回） */
    @GetMapping("/audit/page")
    public R<Map<String, Object>> auditPage(@RequestParam(required = false) Integer reviewStatus,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        return R.ok(CampaignView.page(campaignService.merchantAuditPage(page, size, reviewStatus)));
    }

    /** 审核商户促销：approved=true 通过（reviewStatus=1）/ 驳回（reviewStatus=2，note 必填） */
    @PostMapping("/audit/{id}/review")
    public R<CampaignView> review(@PathVariable Long id,
                                  @RequestBody Map<String, Object> body,
                                  HttpServletRequest request) {
        boolean approved = Boolean.TRUE.equals(body == null ? null : body.get("approved"));
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        var c = campaignService.merchantReview(id, approved, note);
        auditService.record(UserContext.currentUserId(request), "CAMPAIGN_REVIEW",
                (approved ? "商户促销审核通过" : "商户促销驳回") + " #" + id
                        + (note == null || note.isBlank() ? "" : "：" + note), request);
        return R.ok(approved ? "审核通过" : "已驳回", CampaignView.of(c));
    }
}
