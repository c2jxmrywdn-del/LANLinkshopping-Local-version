package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.entity.SysConfig;
import com.lanlink.shopping.service.SysConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理后台：系统动态配置（改后立即生效，无需重启）。受 AuthInterceptor 的 admin 角色保护。
 */
@RestController
@RequestMapping("/admin/config")
public class AdminConfigController {

    private final SysConfigService config;

    public AdminConfigController(SysConfigService config) {
        this.config = config;
    }

    @GetMapping("/list")
    public R<List<SysConfig>> list() {
        return R.ok(config.list());
    }

    @PostMapping("/set")
    public R<Void> set(@RequestParam String cfgKey, @RequestParam String cfgValue) {
        config.set(cfgKey, cfgValue);
        return R.ok("配置已更新并即时生效", null);
    }

    @PostMapping("/refresh")
    public R<Void> refresh() {
        config.refresh();
        return R.ok("缓存已刷新", null);
    }
}
