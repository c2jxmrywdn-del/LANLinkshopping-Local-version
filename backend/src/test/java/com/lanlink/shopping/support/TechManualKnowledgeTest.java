package com.lanlink.shopping.support;

import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import com.lanlink.shopping.module.techservice.service.TechKnowledgeComposer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 机制说明型知识块：实体画像只能回答「有什么/是谁」，答不了「怎么办」。
 * 2026-11-04 容器实测：问「平台的技术经纪人是怎么引入的」召回 0 条并拒答。
 */
class TechManualKnowledgeTest {

    private final TechComplianceGuard guard = new TechComplianceGuard();
    private final TechKnowledgeComposer composer = new TechKnowledgeComposer(guard);

    @Test
    void processChunksCoverTheQuestionsEntitiesCannotAnswer() {
        List<TechKnowledgeComposer.Chunk> manual = composer.manualChunks();

        assertFalse(manual.isEmpty(), "必须有一类机制说明语料");
        String all = manual.stream().map(TechKnowledgeComposer.Chunk::text).reduce("", (a, b) -> a + "\n" + b);

        assertTrue(all.contains("邀请") && all.contains("审核"), "专家与技术经纪人的引入流程");
        assertTrue(all.contains("里程碑") || all.contains("陪跑"), "全流程陪跑的交付节点");
        assertTrue(all.contains("快筛") && all.contains("第三方"), "AI 快筛与正式评价的分工");
        assertTrue(manual.stream().allMatch(c -> TechKnowledgeComposer.SOURCE_MANUAL.equals(c.source())),
                "机制块统一走 tech-manual 源，便于单独重灌与降权");
    }

    @Test
    void serviceLineChunksComeFromTheSameBoundarySourceAsTheConsole() {
        List<TechKnowledgeComposer.Chunk> lines = composer.boundaryChunks();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) guard.boundary().get("items");
        assertEquals(items.size(), lines.size(), "服务线条数必须与后台展示的边界清单一致，不能两处各写一份");

        TechKnowledgeComposer.Chunk first = lines.get(0);
        assertTrue(first.text().contains(String.valueOf(title(items.get(0)))),
                "每条服务线的边界文案取自同一数据源");
        assertTrue(first.text().contains("不得") || first.text().contains("禁止") || first.text().contains("不可"),
                "服务线块必须同时带上不能做什么，否则模型只记得能力不记得边界");
    }

    @Test
    void boundaryChunksNeverLeakCredentialNumbersOrContactDetails() {
        for (TechKnowledgeComposer.Chunk c : composer.boundaryChunks()) {
            assertFalse(c.text().contains("licenseNo"), "机构证照等核验材料不进任何知识块");
            assertTrue(c.refId().startsWith("line:"), "服务线块用可寻址的稳定 refId");
        }
    }

    private static Object title(Map<String, Object> item) {
        return item.get("module");
    }
}
