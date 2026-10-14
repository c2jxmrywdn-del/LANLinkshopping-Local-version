package com.lanlink.cloud.design.gallery;

import com.lanlink.cloud.design.gallery.GalleryModels.ShowcaseCase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 精选前端作品数据集（内置精选，参照 21st.dev 分类体系）。
 * 数据来源策略：内置精选数据集（当前）→ 后续可接入真实抓取/投稿源，本类作为唯一数据访问点便于替换。
 */
@Component
public class GalleryDataset {

    private final List<ShowcaseCase> cases = new ArrayList<>();

    public GalleryDataset() {
        cases.add(caseOf("case-001", "Nova SaaS 落地页", "深色渐变 Hero + 滚动视差的 SaaS 官网模板",
                "minimal", List.of("nextjs", "react", "tailwind"), "landing", "tech", "2026-09-12", 1280,
                List.of("hero", "gradient", "pricing")));
        cases.add(caseOf("case-002", "Aurora 数据看板", "玻璃拟态风格的实时数据监控看板",
                "glassmorphism", List.of("vue", "echarts", "pinia"), "dashboard", "finance", "2026-08-28", 986,
                List.of("charts", "glass", "dark")));
        cases.add(caseOf("case-003", "Bazaar 电商前台", "Material 风格的多品类电商购物模板",
                "material", List.of("react", "nextjs", "mui"), "ecommerce", "retail", "2026-09-30", 1450,
                List.of("cart", "product-grid", "filters")));
        cases.add(caseOf("case-004", "Folio 设计师作品集", "大字排版 + 粗野主义的个人作品集",
                "brutalism", List.of("svelte", "sveltekit"), "portfolio", "media", "2026-07-15", 742,
                List.of("typography", "grid", "gallery")));
        cases.add(caseOf("case-005", "Pulse 医疗预约", "清爽友好的在线预约与健康管理界面",
                "minimal", List.of("vue", "vite", "element-plus"), "saas", "healthcare", "2026-09-05", 623,
                List.of("form", "calendar", "booking")));
        cases.add(caseOf("case-006", "RetroBlog 个人博客", "复古像素风的博客阅读体验",
                "retro", List.of("astro", "tailwind"), "blog", "media", "2026-06-20", 415,
                List.of("reading", "pixel", "dark")));
        cases.add(caseOf("case-007", "EduPath 在线教育", "明亮活泼的课程学习平台界面",
                "bright", List.of("react", "nextjs", "tailwind"), "saas", "education", "2026-09-18", 891,
                List.of("course", "progress", "video")));
        cases.add(caseOf("case-008", "Vault 金融理财", "稳重可信的资产与投资组合管理台",
                "dark", List.of("vue", "echarts", "vite"), "dashboard", "finance", "2026-08-02", 1102,
                List.of("charts", "tables", "fintech")));
        cases.add(caseOf("case-009", "Bloom 品牌活动页", "高饱和艺术风格的营销活动专题页",
                "art", List.of("react", "gsap", "tailwind"), "landing", "media", "2026-10-01", 1338,
                List.of("animation", "campaign", "popup")));
        cases.add(caseOf("case-010", "GridWorks 企业官网", "极简网格布局的 B2B 企业官网",
                "minimal", List.of("vue", "nuxt"), "landing", "tech", "2026-05-11", 534,
                List.of("grid", "corporate", "scroll")));
    }

    private static ShowcaseCase caseOf(String id, String title, String desc, String style,
                                       List<String> tech, String scenario, String industry,
                                       String published, int likes, List<String> tags) {
        ShowcaseCase c = new ShowcaseCase();
        c.setCaseId(id);
        c.setTitle(title);
        c.setDescription(desc);
        c.setCoverUrl("https://placehold.co/640x400?text=" + id);
        c.setDemoUrl("https://demo.21st-style.local/" + id);
        c.setSourceUrl("https://21st.dev/@" + id);
        c.setAuthor("精选编辑部");
        c.setStyle(style);
        c.setTechStack(tech);
        c.setScenario(scenario);
        c.setIndustry(industry);
        c.setPublishedAt(published);
        c.setLikeCount(likes);
        c.setTags(tags);
        return c;
    }

    public List<ShowcaseCase> all() {
        return cases;
    }
}
