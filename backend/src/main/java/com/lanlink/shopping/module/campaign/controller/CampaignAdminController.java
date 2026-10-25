package com.lanlink.shopping.module.campaign.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.service.CampaignService;
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
 */
@RestController
@RequestMapping("/admin/campaign")
@RequirePerm("admin:all")
public class CampaignAdminController {

    private final CampaignService campaignService;

    public CampaignAdminController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @GetMapping("/page")
    public R<Page<Campaign>> page(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(required = false) Integer status,
                                  @RequestParam(required = false) String keyword) {
        return R.ok(campaignService.adminPage(page, size, type, status, keyword));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(campaignService.adminDetail(id));
    }

    @PostMapping
    public R<Campaign> create(@RequestBody Campaign campaign) {
        return R.ok("活动已创建", campaignService.adminCreate(campaign));
    }

    @PutMapping("/{id}")
    public R<Campaign> update(@PathVariable Long id, @RequestBody Campaign campaign) {
        return R.ok("活动已更新", campaignService.adminUpdate(id, campaign));
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
}
