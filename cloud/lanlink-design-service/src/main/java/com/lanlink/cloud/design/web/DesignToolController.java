package com.lanlink.cloud.design.web;

import com.lanlink.cloud.common.R;
import com.lanlink.cloud.design.adapter.AdapterRegistry;
import com.lanlink.cloud.design.adapter.DesignToolAdapter;
import com.lanlink.cloud.design.model.DesignModels.*;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设计工具对接统一入口（网关路由 /api/design/** → 本服务）。
 * 通过 {tool} 路径参数路由到对应适配器（figma / canva / mock），
 * 调用方只面向统一模型，不感知具体工具差异 → 插件化低耦合。
 */
@RestController
@RequestMapping("/design")
public class DesignToolController {

    private final AdapterRegistry registry;

    public DesignToolController(AdapterRegistry registry) {
        this.registry = registry;
    }

    /** 适配器能力矩阵（前端工具接入展示 + 扩展性验证） */
    @GetMapping("/tools")
    public R<List<Map<String, Object>>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DesignToolAdapter a : registry.all()) {
            out.add(Map.of(
                    "toolId", a.toolId(),
                    "displayName", a.displayName(),
                    "capabilities", a.capabilities()
            ));
        }
        return R.ok(out);
    }

    // ===== OAuth 2.0 授权 =====

    @GetMapping("/auth/{tool}/url")
    public R<AuthUrlVO> authUrl(@PathVariable String tool, @RequestParam(required = false) String state) {
        return R.ok(registry.get(tool).buildAuthUrl(state == null ? "demo-state" : state));
    }

    @PostMapping("/auth/{tool}/token")
    public R<TokenVO> exchangeToken(@PathVariable String tool, @RequestBody TokenRequest req) {
        return R.ok(registry.get(tool).exchangeToken(req.code()));
    }

    @PostMapping("/auth/{tool}/refresh")
    public R<TokenVO> refresh(@PathVariable String tool, @RequestBody RefreshRequest req) {
        return R.ok(registry.get(tool).refreshToken(req.refreshToken()));
    }

    // ===== 设计文件 / 组件 / 令牌 / 预览 / 增量同步 =====

    @GetMapping("/{tool}/files/{fileKey}")
    public R<DesignFileVO> designFile(@PathVariable String tool, @PathVariable String fileKey,
                                      @RequestHeader(value = "X-Design-Token", required = false) String token) {
        return R.ok(registry.get(tool).getDesignFile(token, fileKey));
    }

    @PostMapping("/{tool}/files/{fileKey}/components")
    public R<List<DesignComponentVO>> components(@PathVariable String tool, @PathVariable String fileKey,
                                                 @RequestHeader(value = "X-Design-Token", required = false) String token,
                                                 @RequestBody(required = false) ComponentQuery req) {
        List<String> ids = req == null ? null : req.componentIds();
        return R.ok(registry.get(tool).listComponents(token, fileKey, ids));
    }

    @GetMapping("/{tool}/files/{fileKey}/tokens")
    public R<DesignTokenSet> tokens(@PathVariable String tool, @PathVariable String fileKey,
                                    @RequestHeader(value = "X-Design-Token", required = false) String token) {
        return R.ok(registry.get(tool).extractTokens(token, fileKey));
    }

    @GetMapping("/{tool}/files/{fileKey}/preview")
    public R<PreviewVO> preview(@PathVariable String tool, @PathVariable String fileKey,
                                @RequestParam(required = false) String nodeId,
                                @RequestHeader(value = "X-Design-Token", required = false) String token) {
        return R.ok(registry.get(tool).getPreview(token, fileKey, nodeId));
    }

    /** 增量同步（要求 ≤3s 返回，前端 3 秒轮询/推送） */
    @GetMapping("/{tool}/files/{fileKey}/sync")
    public R<SyncResultVO> sync(@PathVariable String tool, @PathVariable String fileKey,
                                @RequestParam(required = false) String sinceVersion,
                                @RequestHeader(value = "X-Design-Token", required = false) String token) {
        return R.ok(registry.get(tool).syncChanges(token, fileKey, sinceVersion));
    }

    // ===== 模板库 / 编辑器嵌入 / 成果回传 =====

    @PostMapping("/{tool}/templates")
    public R<List<TemplateVO>> templates(@PathVariable String tool,
                                         @RequestHeader(value = "X-Design-Token", required = false) String token,
                                         @RequestBody(required = false) TemplateQuery query) {
        return R.ok(registry.get(tool).listTemplates(token, query));
    }

    @PostMapping("/{tool}/embed")
    public R<EmbedSpecVO> embed(@PathVariable String tool,
                                @RequestHeader(value = "X-Design-Token", required = false) String token,
                                @RequestBody EmbedRequest req) {
        return R.ok(registry.get(tool).buildEmbedSpec(token, req.designId()));
    }

    @PostMapping("/{tool}/callback")
    public R<CallbackResultVO> callback(@PathVariable String tool,
                                        @RequestHeader(value = "X-Design-Token", required = false) String token,
                                        @RequestBody CallbackRequest req) {
        return R.ok(registry.get(tool).submitCallback(token, req.designId(), req.exportUrl()));
    }

    // ===== 请求体契约 =====

    public record TokenRequest(String code) {}
    public record RefreshRequest(String refreshToken) {}
    public record ComponentQuery(List<String> componentIds) {}
    public record EmbedRequest(String designId) {}
    public record CallbackRequest(String designId, String exportUrl) {}
}
