package com.lanlink.cloud.design.adapter.mock;

import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.design.adapter.DesignToolAdapter;
import com.lanlink.cloud.design.model.DesignModels.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Mock 演示适配器：内置完整演示数据，实现全部 9 项能力。
 * 用途：无外部凭据时可完整演示设计对接全流程（OAuth 跳转→文件→组件→令牌→预览→增量同步→模板→嵌入→回传）。
 * live 模式（lanlink.design.mode=live）下 Figma/Canva 适配器优先，本适配器仍保留用于离线演示与测试。
 */
@Component
public class MockDesignAdapter implements DesignToolAdapter {

    /** 演示文件版本游标 → 变更节点映射（增量同步演示） */
    private static final Map<String, List<String>> DEMO_CHANGES = Map.of(
            "v1", List.of("1:1", "1:2"),
            "v2", List.of("1:3"),
            "v3", List.of()
    );

    @Override
    public String toolId() {
        return "mock";
    }

    @Override
    public String displayName() {
        return "Mock 设计工具（演示）";
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.allOf(Capability.class);
    }

    // ===== OAuth 2.0 授权（演示流程） =====

    @Override
    public AuthUrlVO buildAuthUrl(String state) {
        AuthUrlVO vo = new AuthUrlVO();
        vo.setState(state);
        // 演示环境指向本服务自身的模拟授权页，前端可完整走通跳转链路
        vo.setAuthUrl("http://localhost:8300/api/design/auth/mock/authorize?state=" + state);
        return vo;
    }

    @Override
    public TokenVO exchangeToken(String code) {
        if (code == null || code.isBlank()) throw new BusinessException(400, "授权码不能为空");
        TokenVO t = new TokenVO();
        t.setAccessToken("mock-access-" + UUID.randomUUID().toString().substring(0, 8));
        t.setRefreshToken("mock-refresh-" + UUID.randomUUID().toString().substring(0, 8));
        t.setExpiresIn(3600L);
        t.setTokenType("Bearer");
        t.setScope("files:read components:read");
        return t;
    }

    @Override
    public TokenVO refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw new BusinessException(400, "refresh_token 不能为空");
        return exchangeToken("refresh-" + refreshToken);
    }

    // ===== 设计文件与组件资源 =====

    @Override
    public DesignFileVO getDesignFile(String accessToken, String fileKey) {
        requireToken(accessToken);
        DesignFileVO file = new DesignFileVO();
        file.setFileKey(fileKey == null || fileKey.isBlank() ? "demo-file-001" : fileKey);
        file.setName("LANLink 商城设计稿（演示）");
        file.setLastModified(LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        file.setThumbnailUrl("https://placehold.co/320x200?text=Design+File");

        PageNode p1 = new PageNode();
        p1.setNodeId("0:1");
        p1.setName("商城首页");
        FrameNode f1 = new FrameNode();
        f1.setNodeId("1:1"); f1.setName("首页顶部导航"); f1.setType("FRAME");
        f1.setWidth(new BigDecimal("1440")); f1.setHeight(new BigDecimal("80"));
        FrameNode f2 = new FrameNode();
        f2.setNodeId("1:2"); f2.setName("商品卡片网格"); f2.setType("COMPONENT_SET");
        f2.setWidth(new BigDecimal("1440")); f2.setHeight(new BigDecimal("960"));
        p1.setFrames(List.of(f1, f2));

        PageNode p2 = new PageNode();
        p2.setNodeId("0:2");
        p2.setName("订单流程");
        FrameNode f3 = new FrameNode();
        f3.setNodeId("1:3"); f3.setName("结算支付表单"); f3.setType("FRAME");
        f3.setWidth(new BigDecimal("720")); f3.setHeight(new BigDecimal("900"));
        p2.setFrames(List.of(f3));

        file.setPages(List.of(p1, p2));
        return file;
    }

    @Override
    public List<DesignComponentVO> listComponents(String accessToken, String fileKey, List<String> componentIds) {
        requireToken(accessToken);
        List<DesignComponentVO> all = new ArrayList<>();
        all.add(component("cmp-btn-primary", "按钮/主要按钮", "1:10", 120, 40));
        all.add(component("cmp-card-product", "卡片/商品卡片", "1:20", 320, 400));
        all.add(component("cmp-nav-header", "导航/顶部导航栏", "1:30", 1440, 80));
        all.add(component("cmp-form-checkout", "表单/结算表单", "1:40", 720, 560));
        if (componentIds == null || componentIds.isEmpty()) return all;
        Set<String> want = new HashSet<>(componentIds);
        all.removeIf(c -> !want.contains(c.getComponentId()));
        return all;
    }

    @Override
    public DesignTokenSet extractTokens(String accessToken, String fileKey) {
        requireToken(accessToken);
        DesignTokenSet ts = new DesignTokenSet();
        ts.setFileKey(fileKey == null ? "demo-file-001" : fileKey);
        ts.setVersion("v3");
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("color.primary", "#1E6EB8");
        colors.put("color.primary.hover", "#175A99");
        colors.put("color.danger", "#E4393C");
        colors.put("color.success", "#10B981");
        colors.put("color.text.primary", "#13233A");
        colors.put("color.text.secondary", "#64748B");
        colors.put("color.bg.page", "#F7F9FC");
        ts.setColors(colors);

        Map<String, FontToken> typo = new LinkedHashMap<>();
        FontToken h = new FontToken();
        h.setFontFamily("Inter, 'PingFang SC', sans-serif");
        h.setFontSize(28); h.setFontWeight("700"); h.setLineHeight(new BigDecimal("1.3"));
        typo.put("font.heading.lg", h);
        FontToken body = new FontToken();
        body.setFontFamily("Inter, 'PingFang SC', sans-serif");
        body.setFontSize(14); body.setFontWeight("400"); body.setLineHeight(new BigDecimal("1.6"));
        typo.put("font.body.md", body);
        ts.setTypography(typo);

        Map<String, String> spacing = new LinkedHashMap<>();
        spacing.put("space.1", "4px"); spacing.put("space.2", "8px");
        spacing.put("space.3", "12px"); spacing.put("space.4", "16px");
        spacing.put("space.6", "24px"); spacing.put("space.8", "32px");
        ts.setSpacing(spacing);

        Map<String, String> radii = new LinkedHashMap<>();
        radii.put("radius.sm", "6px"); radii.put("radius.md", "10px"); radii.put("radius.lg", "18px");
        ts.setRadii(radii);

        Map<String, String> shadows = new LinkedHashMap<>();
        shadows.put("shadow.card", "0 8px 24px rgba(19,35,58,0.08)");
        shadows.put("shadow.popover", "0 16px 40px rgba(19,35,58,0.12)");
        ts.setShadows(shadows);
        return ts;
    }

    // ===== 实时预览与增量更新 =====

    @Override
    public PreviewVO getPreview(String accessToken, String fileKey, String nodeId) {
        requireToken(accessToken);
        PreviewVO p = new PreviewVO();
        p.setFileKey(fileKey == null ? "demo-file-001" : fileKey);
        p.setNodeId(nodeId == null ? "1:1" : nodeId);
        p.setImageUrl("https://placehold.co/960x540?text=Preview+" + (nodeId == null ? "1:1" : nodeId));
        p.setVersion("v3");
        p.setRenderedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return p;
    }

    @Override
    public SyncResultVO syncChanges(String accessToken, String fileKey, String sinceVersion) {
        long start = System.currentTimeMillis();
        requireToken(accessToken);
        String prev = sinceVersion == null || sinceVersion.isBlank() ? "v0" : sinceVersion;
        SyncResultVO r = new SyncResultVO();
        r.setFileKey(fileKey == null ? "demo-file-001" : fileKey);
        r.setPreviousVersion(prev);
        r.setCurrentVersion("v3");
        // 演示：从 sinceVersion 之后累计变更节点
        List<String> changed = new ArrayList<>();
        boolean after = "v0".equals(prev);
        for (Map.Entry<String, List<String>> e : new TreeMap<>(DEMO_CHANGES).entrySet()) {
            if (after || e.getKey().compareTo(prev) > 0) changed.addAll(e.getValue());
        }
        r.setHasChanges(!changed.isEmpty());
        r.setChangedNodeIds(changed);
        r.setTokens(extractTokens(accessToken, fileKey));
        // 模拟增量同步在 3 秒内完成（演示数据毫秒级返回）
        r.setElapsedMs(System.currentTimeMillis() - start);
        return r;
    }

    // ===== 模板库 / 编辑器嵌入 / 成果回传 =====

    @Override
    public List<TemplateVO> listTemplates(String accessToken, TemplateQuery query) {
        requireToken(accessToken);
        List<TemplateVO> all = new ArrayList<>();
        all.add(template("tpl-landing", "SaaS 落地页模板", "landing", "minimal"));
        all.add(template("tpl-dashboard", "数据看板模板", "dashboard", "dark"));
        all.add(template("tpl-ecommerce", "电商商品详情模板", "ecommerce", "bright"));
        all.add(template("tpl-form", "多步骤表单模板", "form", "material"));
        if (query == null) return all;
        String kw = query.getKeyword() == null ? "" : query.getKeyword().trim().toLowerCase();
        String cat = query.getCategory();
        String style = query.getStyle();
        all.removeIf(t ->
                (!kw.isEmpty() && !t.getTitle().toLowerCase().contains(kw))
                        || (cat != null && !cat.isBlank() && !cat.equals(t.getCategory()))
                        || (style != null && !style.isBlank() && !style.equals(t.getStyle())));
        return all;
    }

    @Override
    public EmbedSpecVO buildEmbedSpec(String accessToken, String designId) {
        requireToken(accessToken);
        EmbedSpecVO e = new EmbedSpecVO();
        e.setEmbedUrl("https://embed.mock-design.local/editor?design=" + (designId == null ? "demo" : designId));
        e.setMode("edit");
        e.setWidth(1280);
        e.setHeight(720);
        e.setOptions(Map.of("theme", "light", "locale", "zh-CN", "autosave", "true"));
        return e;
    }

    @Override
    public CallbackResultVO submitCallback(String accessToken, String designId, String exportUrl) {
        requireToken(accessToken);
        if (exportUrl == null || exportUrl.isBlank()) throw new BusinessException(400, "成果地址 exportUrl 不能为空");
        CallbackResultVO r = new CallbackResultVO();
        r.setSuccess(true);
        r.setDesignId(designId == null ? "demo-design" : designId);
        r.setExportUrl(exportUrl);
        r.setReceivedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        r.setMessage("设计成果已回传（演示）");
        return r;
    }

    // ===== 内部工具 =====

    private void requireToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException(401, "缺少访问令牌，请先完成 OAuth 授权");
        }
    }

    private static DesignComponentVO component(String id, String name, String nodeId, int w, int h) {
        DesignComponentVO c = new DesignComponentVO();
        c.setComponentId(id); c.setName(name); c.setNodeId(nodeId);
        c.setExportUrl("https://placehold.co/" + w + "x" + h + "?text=" + nodeId);
        c.setFormat("PNG");
        c.setWidth(new BigDecimal(w)); c.setHeight(new BigDecimal(h));
        return c;
    }

    private static TemplateVO template(String id, String title, String category, String style) {
        TemplateVO t = new TemplateVO();
        t.setTemplateId(id); t.setTitle(title); t.setCategory(category); t.setStyle(style);
        t.setThumbnailUrl("https://placehold.co/400x300?text=" + title);
        t.setEditUrl("https://embed.mock-design.local/template/" + id);
        return t;
    }
}
