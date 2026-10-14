package com.lanlink.cloud.design.gallery;

import com.lanlink.cloud.common.BusinessException;
import com.lanlink.cloud.design.gallery.GalleryModels.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 资源库检索服务：多维组合筛选（风格/技术栈/场景/行业/时间范围）+ 关键词检索 + 排序 + 分页。
 */
@Service
public class GalleryService {

    private final GalleryDataset dataset;

    public GalleryService(GalleryDataset dataset) {
        this.dataset = dataset;
    }

    /** 多维组合筛选 + 关键词检索 */
    public PageResult<ShowcaseCase> search(GalleryQuery q) {
        if (q == null) q = new GalleryQuery();
        String kw = q.getKeyword() == null ? "" : q.getKeyword().trim().toLowerCase(Locale.ROOT);
        List<ShowcaseCase> filtered = new ArrayList<>();
        for (ShowcaseCase c : dataset.all()) {
            if (!kw.isEmpty() && !matchesKeyword(c, kw)) continue;
            if (notBlank(q.getStyle()) && !q.getStyle().equals(c.getStyle())) continue;
            if (notBlank(q.getTechStack()) && (c.getTechStack() == null || !c.getTechStack().contains(q.getTechStack()))) continue;
            if (notBlank(q.getScenario()) && !q.getScenario().equals(c.getScenario())) continue;
            if (notBlank(q.getIndustry()) && !q.getIndustry().equals(c.getIndustry())) continue;
            if (notBlank(q.getDateFrom()) && c.getPublishedAt() != null && c.getPublishedAt().compareTo(q.getDateFrom()) < 0) continue;
            if (notBlank(q.getDateTo()) && c.getPublishedAt() != null && c.getPublishedAt().compareTo(q.getDateTo()) > 0) continue;
            filtered.add(c);
        }
        // 排序：latest（发布时间倒序，默认）/ likes（热度倒序）
        if ("likes".equalsIgnoreCase(q.getSortBy())) {
            filtered.sort((a, b) -> Integer.compare(
                    b.getLikeCount() == null ? 0 : b.getLikeCount(),
                    a.getLikeCount() == null ? 0 : a.getLikeCount()));
        } else {
            filtered.sort((a, b) -> b.getPublishedAt().compareTo(a.getPublishedAt()));
        }
        // 分页
        int page = q.getPage() == null || q.getPage() < 1 ? 1 : q.getPage();
        int size = q.getSize() == null || q.getSize() < 1 ? 12 : Math.min(q.getSize(), 50);
        int from = (page - 1) * size;
        List<ShowcaseCase> records = from >= filtered.size()
                ? List.of() : filtered.subList(from, Math.min(from + size, filtered.size()));
        PageResult<ShowcaseCase> out = new PageResult<>();
        out.setRecords(records);
        out.setTotal(filtered.size());
        out.setPage(page);
        out.setSize(size);
        return out;
    }

    /** 案例详情 */
    public ShowcaseCase detail(String caseId) {
        return dataset.all().stream()
                .filter(c -> c.getCaseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "案例不存在: " + caseId));
    }

    /** 筛选维度可选值（前端渲染筛选器） */
    public List<FacetVO> facets() {
        Set<String> styles = new LinkedHashSet<>();
        Set<String> techs = new LinkedHashSet<>();
        Set<String> scenarios = new LinkedHashSet<>();
        Set<String> industries = new LinkedHashSet<>();
        for (ShowcaseCase c : dataset.all()) {
            styles.add(c.getStyle());
            if (c.getTechStack() != null) techs.addAll(c.getTechStack());
            scenarios.add(c.getScenario());
            industries.add(c.getIndustry());
        }
        List<FacetVO> out = new ArrayList<>();
        out.add(facet("style", "设计风格", styles));
        out.add(facet("techStack", "技术栈", techs));
        out.add(facet("scenario", "应用场景", scenarios));
        out.add(facet("industry", "行业分类", industries));
        return out;
    }

    private static FacetVO facet(String field, String label, Set<String> values) {
        FacetVO f = new FacetVO();
        f.setField(field);
        f.setLabel(label);
        f.setValues(List.copyOf(values));
        return f;
    }

    private static boolean matchesKeyword(ShowcaseCase c, String kw) {
        if (c.getTitle().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (c.getDescription().toLowerCase(Locale.ROOT).contains(kw)) return true;
        if (c.getTags() != null && c.getTags().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(kw))) return true;
        return c.getAuthor() != null && c.getAuthor().toLowerCase(Locale.ROOT).contains(kw);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
