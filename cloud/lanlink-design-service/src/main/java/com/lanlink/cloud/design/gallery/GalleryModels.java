package com.lanlink.cloud.design.gallery;

import java.util.List;

/**
 * 前端作品资源库统一数据模型（参照 21st.dev 分类体系：风格/技术栈/场景/行业/时间）。
 */
public final class GalleryModels {

    private GalleryModels() {}

    /** 精选前端作品案例 */
    public static class ShowcaseCase {
        private String caseId;
        private String title;
        private String description;
        private String coverUrl;
        private String demoUrl;
        private String sourceUrl;
        private String author;
        /** 设计风格：minimal / dark / glassmorphism / brutalism / material / retro ... */
        private String style;
        /** 技术栈：react / vue / nextjs / svelte / tailwind / sveltekit ... */
        private List<String> techStack;
        /** 应用场景：landing / dashboard / ecommerce / portfolio / saas / blog ... */
        private String scenario;
        /** 行业分类：tech / finance / healthcare / education / retail / media ... */
        private String industry;
        /** 发布时间（yyyy-MM-dd） */
        private String publishedAt;
        private Integer likeCount;
        private List<String> tags;

        public String getCaseId() { return caseId; }
        public void setCaseId(String caseId) { this.caseId = caseId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getCoverUrl() { return coverUrl; }
        public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
        public String getDemoUrl() { return demoUrl; }
        public void setDemoUrl(String demoUrl) { this.demoUrl = demoUrl; }
        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }
        public List<String> getTechStack() { return techStack; }
        public void setTechStack(List<String> techStack) { this.techStack = techStack; }
        public String getScenario() { return scenario; }
        public void setScenario(String scenario) { this.scenario = scenario; }
        public String getIndustry() { return industry; }
        public void setIndustry(String industry) { this.industry = industry; }
        public String getPublishedAt() { return publishedAt; }
        public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }
        public Integer getLikeCount() { return likeCount; }
        public void setLikeCount(Integer likeCount) { this.likeCount = likeCount; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    /** 资源库多维检索条件（组合筛选 + 关键词） */
    public static class GalleryQuery {
        private String keyword;
        private String style;
        private String techStack;
        private String scenario;
        private String industry;
        private String dateFrom;
        private String dateTo;
        private String sortBy;   // latest | likes
        private Integer page;
        private Integer size;

        public String getKeyword() { return keyword; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }
        public String getTechStack() { return techStack; }
        public void setTechStack(String techStack) { this.techStack = techStack; }
        public String getScenario() { return scenario; }
        public void setScenario(String scenario) { this.scenario = scenario; }
        public String getIndustry() { return industry; }
        public void setIndustry(String industry) { this.industry = industry; }
        public String getDateFrom() { return dateFrom; }
        public void setDateFrom(String dateFrom) { this.dateFrom = dateFrom; }
        public String getDateTo() { return dateTo; }
        public void setDateTo(String dateTo) { this.dateTo = dateTo; }
        public String getSortBy() { return sortBy; }
        public void setSortBy(String sortBy) { this.sortBy = sortBy; }
        public Integer getPage() { return page; }
        public void setPage(Integer page) { this.page = page; }
        public Integer getSize() { return size; }
        public void setSize(Integer size) { this.size = size; }
    }

    /** 分页结果 */
    public static class PageResult<T> {
        private List<T> records;
        private long total;
        private int page;
        private int size;
        public List<T> getRecords() { return records; }
        public void setRecords(List<T> records) { this.records = records; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
    }

    /** 筛选维度可选值（前端渲染筛选器） */
    public static class FacetVO {
        private String field;
        private String label;
        private List<String> values;
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
    }
}
