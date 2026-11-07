package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.module.techservice.api.TechP1Views;
import com.lanlink.shopping.module.techservice.service.OpenLicenseQueryService;
import com.lanlink.shopping.module.techservice.service.TechPilotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** P1 公开目录（无需登录）：中试资源库 + 开放许可专区材料包。 */
@RestController
@RequestMapping("/tech/public")
public class TechP1PublicController {

    private final TechPilotService pilotService;
    private final OpenLicenseQueryService openLicenseQuery;

    public TechP1PublicController(TechPilotService pilotService,
                                  OpenLicenseQueryService openLicenseQuery) {
        this.pilotService = pilotService;
        this.openLicenseQuery = openLicenseQuery;
    }

    @GetMapping("/pilot-resources")
    public R<List<TechP1Views.PilotResource>> pilotResources(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String region) {
        return R.ok(pilotService.publicResources(type, region).stream()
                .map(TechP1Views::ofPilotResource).toList());
    }

    @GetMapping("/open-license")
    public R<List<TechP1Views.OpenLicenseKit>> openLicense() {
        return R.ok(openLicenseQuery.openLicenseAchievements().stream()
                .map(TechP1Views::ofOpenLicenseKit).toList());
    }
}
