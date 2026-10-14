package com.lanlink.cloud.design.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 设计工具对接统一数据模型（跨适配器标准化，前端只认这一套结构）。
 * 全部为纯数据载体，集中在同一文件便于维护与查阅。
 */
public final class DesignModels {

    private DesignModels() {}

    /** OAuth 授权跳转地址 */
    public static class AuthUrlVO {
        private String authUrl;
        private String state;
        public String getAuthUrl() { return authUrl; }
        public void setAuthUrl(String authUrl) { this.authUrl = authUrl; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
    }

    /** OAuth 令牌 */
    public static class TokenVO {
        private String accessToken;
        private String refreshToken;
        private Long expiresIn;
        private String tokenType;
        private String scope;
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public Long getExpiresIn() { return expiresIn; }
        public void setExpiresIn(Long expiresIn) { this.expiresIn = expiresIn; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }

    /** 设计文件概要 */
    public static class DesignFileVO {
        private String fileKey;
        private String name;
        private String lastModified;
        private String thumbnailUrl;
        private List<PageNode> pages;
        public String getFileKey() { return fileKey; }
        public void setFileKey(String fileKey) { this.fileKey = fileKey; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLastModified() { return lastModified; }
        public void setLastModified(String lastModified) { this.lastModified = lastModified; }
        public String getThumbnailUrl() { return thumbnailUrl; }
        public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
        public List<PageNode> getPages() { return pages; }
        public void setPages(List<PageNode> pages) { this.pages = pages; }
    }

    /** 页面节点 */
    public static class PageNode {
        private String nodeId;
        private String name;
        private List<FrameNode> frames;
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<FrameNode> getFrames() { return frames; }
        public void setFrames(List<FrameNode> frames) { this.frames = frames; }
    }

    /** 画板/组件节点 */
    public static class FrameNode {
        private String nodeId;
        private String name;
        private String type;
        private BigDecimal width;
        private BigDecimal height;
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public BigDecimal getWidth() { return width; }
        public void setWidth(BigDecimal width) { this.width = width; }
        public BigDecimal getHeight() { return height; }
        public void setHeight(BigDecimal height) { this.height = height; }
    }

    /** 组件资源（含导出图） */
    public static class DesignComponentVO {
        private String componentId;
        private String name;
        private String nodeId;
        private String exportUrl;
        private String format;
        private BigDecimal width;
        private BigDecimal height;
        public String getComponentId() { return componentId; }
        public void setComponentId(String componentId) { this.componentId = componentId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public String getExportUrl() { return exportUrl; }
        public void setExportUrl(String exportUrl) { this.exportUrl = exportUrl; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public BigDecimal getWidth() { return width; }
        public void setWidth(BigDecimal width) { this.width = width; }
        public BigDecimal getHeight() { return height; }
        public void setHeight(BigDecimal height) { this.height = height; }
    }

    /** 设计令牌集合（颜色/字体/间距/圆角/阴影 统一标准化） */
    public static class DesignTokenSet {
        private String fileKey;
        private String version;
        private Map<String, String> colors;
        private Map<String, FontToken> typography;
        private Map<String, String> spacing;
        private Map<String, String> radii;
        private Map<String, String> shadows;
        public String getFileKey() { return fileKey; }
        public void setFileKey(String fileKey) { this.fileKey = fileKey; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public Map<String, String> getColors() { return colors; }
        public void setColors(Map<String, String> colors) { this.colors = colors; }
        public Map<String, FontToken> getTypography() { return typography; }
        public void setTypography(Map<String, FontToken> typography) { this.typography = typography; }
        public Map<String, String> getSpacing() { return spacing; }
        public void setSpacing(Map<String, String> spacing) { this.spacing = spacing; }
        public Map<String, String> getRadii() { return radii; }
        public void setRadii(Map<String, String> radii) { this.radii = radii; }
        public Map<String, String> getShadows() { return shadows; }
        public void setShadows(Map<String, String> shadows) { this.shadows = shadows; }
    }

    /** 字体令牌 */
    public static class FontToken {
        private String fontFamily;
        private Integer fontSize;
        private String fontWeight;
        private BigDecimal lineHeight;
        public String getFontFamily() { return fontFamily; }
        public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
        public Integer getFontSize() { return fontSize; }
        public void setFontSize(Integer fontSize) { this.fontSize = fontSize; }
        public String getFontWeight() { return fontWeight; }
        public void setFontWeight(String fontWeight) { this.fontWeight = fontWeight; }
        public BigDecimal getLineHeight() { return lineHeight; }
        public void setLineHeight(BigDecimal lineHeight) { this.lineHeight = lineHeight; }
    }

    /** 设计稿预览 */
    public static class PreviewVO {
        private String fileKey;
        private String nodeId;
        private String imageUrl;
        private String version;
        private String renderedAt;
        public String getFileKey() { return fileKey; }
        public void setFileKey(String fileKey) { this.fileKey = fileKey; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getRenderedAt() { return renderedAt; }
        public void setRenderedAt(String renderedAt) { this.renderedAt = renderedAt; }
    }

    /** 增量同步结果（要求 ≤3s 返回） */
    public static class SyncResultVO {
        private String fileKey;
        private String previousVersion;
        private String currentVersion;
        private boolean hasChanges;
        private long elapsedMs;
        private List<String> changedNodeIds;
        private DesignTokenSet tokens;
        public String getFileKey() { return fileKey; }
        public void setFileKey(String fileKey) { this.fileKey = fileKey; }
        public String getPreviousVersion() { return previousVersion; }
        public void setPreviousVersion(String previousVersion) { this.previousVersion = previousVersion; }
        public String getCurrentVersion() { return currentVersion; }
        public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }
        public boolean isHasChanges() { return hasChanges; }
        public void setHasChanges(boolean hasChanges) { this.hasChanges = hasChanges; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
        public List<String> getChangedNodeIds() { return changedNodeIds; }
        public void setChangedNodeIds(List<String> changedNodeIds) { this.changedNodeIds = changedNodeIds; }
        public DesignTokenSet getTokens() { return tokens; }
        public void setTokens(DesignTokenSet tokens) { this.tokens = tokens; }
    }

    /** 模板库检索条件 */
    public static class TemplateQuery {
        private String keyword;
        private String category;
        private String style;
        private Integer page;
        private Integer size;
        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }
        public Integer getPage() { return page; }
        public void setPage(Integer page) { this.page = page; }
        public Integer getSize() { return size; }
        public void setSize(Integer size) { this.size = size; }
    }

    /** 模板条目 */
    public static class TemplateVO {
        private String templateId;
        private String title;
        private String category;
        private String style;
        private String thumbnailUrl;
        private String editUrl;
        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }
        public String getThumbnailUrl() { return thumbnailUrl; }
        public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
        public String getEditUrl() { return editUrl; }
        public void setEditUrl(String editUrl) { this.editUrl = editUrl; }
    }

    /** 在线编辑器嵌入规格 */
    public static class EmbedSpecVO {
        private String embedUrl;
        private String mode;
        private Integer width;
        private Integer height;
        private Map<String, String> options;
        public String getEmbedUrl() { return embedUrl; }
        public void setEmbedUrl(String embedUrl) { this.embedUrl = embedUrl; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public Integer getWidth() { return width; }
        public void setWidth(Integer width) { this.width = width; }
        public Integer getHeight() { return height; }
        public void setHeight(Integer height) { this.height = height; }
        public Map<String, String> getOptions() { return options; }
        public void setOptions(Map<String, String> options) { this.options = options; }
    }

    /** 设计成果回传结果 */
    public static class CallbackResultVO {
        private boolean success;
        private String designId;
        private String exportUrl;
        private String receivedAt;
        private String message;
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getDesignId() { return designId; }
        public void setDesignId(String designId) { this.designId = designId; }
        public String getExportUrl() { return exportUrl; }
        public void setExportUrl(String exportUrl) { this.exportUrl = exportUrl; }
        public String getReceivedAt() { return receivedAt; }
        public void setReceivedAt(String receivedAt) { this.receivedAt = receivedAt; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
