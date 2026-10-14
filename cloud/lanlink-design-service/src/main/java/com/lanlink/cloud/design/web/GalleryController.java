package com.lanlink.cloud.design.web;

import com.lanlink.cloud.common.R;
import com.lanlink.cloud.design.codegen.CodeExportService;
import com.lanlink.cloud.design.gallery.GalleryModels.FacetVO;
import com.lanlink.cloud.design.gallery.GalleryModels.GalleryQuery;
import com.lanlink.cloud.design.gallery.GalleryModels.PageResult;
import com.lanlink.cloud.design.gallery.GalleryModels.ShowcaseCase;
import com.lanlink.cloud.design.gallery.GalleryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 前端作品资源库 + 多格式代码导出入口。
 * 路由前缀 /design/gallery/** 与 /design/code/**（经网关 /api/design/** 转发）。
 */
@RestController
@RequestMapping("/design")
public class GalleryController {

    private final GalleryService gallery;
    private final CodeExportService codeExport;

    public GalleryController(GalleryService gallery, CodeExportService codeExport) {
        this.gallery = gallery;
        this.codeExport = codeExport;
    }

    /** 多维组合筛选 + 关键词检索（风格/技术栈/场景/行业/时间） */
    @PostMapping("/gallery/search")
    public R<PageResult<ShowcaseCase>> search(@RequestBody(required = false) GalleryQuery query) {
        return R.ok(gallery.search(query));
    }

    /** 案例详情 */
    @GetMapping("/gallery/{caseId}")
    public R<ShowcaseCase> detail(@PathVariable String caseId) {
        return R.ok(gallery.detail(caseId));
    }

    /** 筛选维度可选值 */
    @GetMapping("/gallery/facets")
    public R<List<FacetVO>> facets() {
        return R.ok(gallery.facets());
    }

    /** 多格式代码导出（REACT/VUE/HTML/CSS）+ 样式变量自动映射 */
    @PostMapping("/code/export")
    public R<CodeExportService.ExportResult> export(@RequestBody ExportRequest req) {
        return R.ok(codeExport.export(req.tool(), req.fileKey(), req.componentId(), req.token(), req.format()));
    }

    public record ExportRequest(String tool, String fileKey, String componentId, String token, String format) {}
}
