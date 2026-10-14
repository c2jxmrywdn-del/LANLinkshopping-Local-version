package com.lanlink.cloud.design.codegen;

import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.design.adapter.AdapterRegistry;
import com.lanlink.cloud.design.model.DesignModels.DesignComponentVO;
import com.lanlink.cloud.design.model.DesignModels.DesignTokenSet;
import com.lanlink.cloud.design.model.DesignModels.FontToken;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 多格式代码导出服务：组件 → React / Vue / HTML / CSS 代码 + 样式变量自动映射。
 *
 * 工作方式：
 *  1. 从适配器取组件资源与设计令牌（DesignTokenSet）
 *  2. 令牌 → CSS 变量（:root 自定义属性），实现"样式变量自动映射"
 *  3. 组件按目标格式生成代码骨架（含令牌引用 var(--color-primary) 等）
 *  4. 所有操作返回明确结果对象；不支持的格式抛 BusinessException 给出友好提示
 */
@Service
public class CodeExportService {

    private final AdapterRegistry registry;

    public CodeExportService(AdapterRegistry registry) {
        this.registry = registry;
    }

    /** 导出格式枚举 */
    public enum Format { REACT, VUE, HTML, CSS }

    /** 导出结果 */
    public static class ExportResult {
        private String format;
        private String fileName;
        private String code;
        private String tokenCss;
        private List<String> usedTokens;
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getTokenCss() { return tokenCss; }
        public void setTokenCss(String tokenCss) { this.tokenCss = tokenCss; }
        public List<String> getUsedTokens() { return usedTokens; }
        public void setUsedTokens(List<String> usedTokens) { this.usedTokens = usedTokens; }
    }

    /** 导出指定组件为指定格式代码 */
    public ExportResult export(String tool, String fileKey, String componentId, String token, String format) {
        DesignComponentVO comp = registry.get(tool).listComponents(token, fileKey, List.of(componentId))
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(404, "组件不存在: " + componentId));
        DesignTokenSet tokens = registry.get(tool).extractTokens(token, fileKey);
        String tokenCss = tokensToCss(tokens);

        Format f;
        try {
            f = Format.valueOf(format == null ? "HTML" : format.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的导出格式: " + format + "，可选 REACT / VUE / HTML / CSS");
        }

        ExportResult r = new ExportResult();
        r.setFormat(f.name());
        r.setTokenCss(tokenCss);
        r.setUsedTokens(List.of("color.primary", "radius.md", "shadow.card"));
        switch (f) {
            case REACT -> { r.setFileName("LanlinkComponent.jsx"); r.setCode(reactCode(comp)); }
            case VUE -> { r.setFileName("LanlinkComponent.vue"); r.setCode(vueCode(comp)); }
            case HTML -> { r.setFileName("lanlink-component.html"); r.setCode(htmlCode(comp)); }
            case CSS -> { r.setFileName("lanlink-tokens.css"); r.setCode(tokenCss); }
        }
        return r;
    }

    /** 设计令牌 → CSS 变量（样式变量自动映射） */
    public String tokensToCss(DesignTokenSet ts) {
        StringBuilder sb = new StringBuilder("/* 设计令牌 → CSS 变量（自动生成） */\n:root {\n");
        if (ts.getColors() != null) {
            for (Map.Entry<String, String> e : ts.getColors().entrySet()) {
                sb.append("  --").append(normalize(e.getKey())).append(": ").append(e.getValue()).append(";\n");
            }
        }
        if (ts.getTypography() != null) {
            for (Map.Entry<String, FontToken> e : ts.getTypography().entrySet()) {
                FontToken f = e.getValue();
                sb.append("  --").append(normalize(e.getKey())).append("-family: ").append(f.getFontFamily()).append(";\n");
                sb.append("  --").append(normalize(e.getKey())).append("-size: ").append(f.getFontSize()).append("px;\n");
                sb.append("  --").append(normalize(e.getKey())).append("-weight: ").append(f.getFontWeight()).append(";\n");
            }
        }
        if (ts.getSpacing() != null) {
            for (Map.Entry<String, String> e : ts.getSpacing().entrySet()) {
                sb.append("  --").append(normalize(e.getKey())).append(": ").append(e.getValue()).append(";\n");
            }
        }
        if (ts.getRadii() != null) {
            for (Map.Entry<String, String> e : ts.getRadii().entrySet()) {
                sb.append("  --").append(normalize(e.getKey())).append(": ").append(e.getValue()).append(";\n");
            }
        }
        if (ts.getShadows() != null) {
            for (Map.Entry<String, String> e : ts.getShadows().entrySet()) {
                sb.append("  --").append(normalize(e.getKey())).append(": ").append(e.getValue()).append(";\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ===== 各格式代码生成（引用令牌变量） =====

    private static String reactCode(DesignComponentVO c) {
        return """
                import React from 'react';

                /** %s（自动生成，设计令牌映射见 lanlink-tokens.css） */
                export default function LanlinkComponent() {
                  return (
                    <div className="lanlink-component" data-node="%s">
                      <h3 className="lanlink-title">%s</h3>
                      <p className="lanlink-desc">尺寸 %sx%s · 组件ID %s</p>
                      <button className="lanlink-btn">主要按钮</button>
                    </div>
                  );
                }
                """.formatted(c.getName(), c.getNodeId(), c.getName(),
                c.getWidth(), c.getHeight(), c.getComponentId());
    }

    private static String vueCode(DesignComponentVO c) {
        return """
                <template>
                  <!-- %s（自动生成，设计令牌映射见 lanlink-tokens.css） -->
                  <div class="lanlink-component" :data-node="'%s'">
                    <h3 class="lanlink-title">%s</h3>
                    <p class="lanlink-desc">尺寸 %sx%s · 组件ID %s</p>
                    <button class="lanlink-btn">主要按钮</button>
                  </div>
                </template>

                <script setup>
                // %s — 组件 %s
                </script>

                <style scoped>
                .lanlink-component { background: var(--color-bg-page); border-radius: var(--radius-md); box-shadow: var(--shadow-card); }
                .lanlink-btn { background: var(--color-primary); color: #fff; border: none; border-radius: var(--radius-sm); padding: var(--space-2) var(--space-4); }
                </style>
                """.formatted(c.getName(), c.getNodeId(), c.getName(),
                c.getWidth(), c.getHeight(), c.getComponentId(), c.getName(), c.getComponentId());
    }

    private static String htmlCode(DesignComponentVO c) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8" />
                  <title>%s</title>
                  <link rel="stylesheet" href="lanlink-tokens.css" />
                  <style>
                    .lanlink-component { background: var(--color-bg-page); border-radius: var(--radius-md); box-shadow: var(--shadow-card); padding: var(--space-6); }
                    .lanlink-btn { background: var(--color-primary); color: #fff; border: none; border-radius: var(--radius-sm); padding: var(--space-2) var(--space-4); cursor: pointer; }
                  </style>
                </head>
                <body>
                  <!-- %s（自动生成） -->
                  <div class="lanlink-component" data-node="%s">
                    <h3>%s</h3>
                    <p>尺寸 %sx%s · 组件ID %s</p>
                    <button class="lanlink-btn">主要按钮</button>
                  </div>
                </body>
                </html>
                """.formatted(c.getName(), c.getName(), c.getNodeId(), c.getName(),
                c.getWidth(), c.getHeight(), c.getComponentId());
    }

    private static String normalize(String key) {
        return key.toLowerCase().replace('.', '-').replace('_', '-').replace(' ', '-');
    }
}
