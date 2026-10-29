package com.lanlink.shopping.module.search;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.aikb.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品向量索引灌库：每小时 :45 增量重建（避开 :15 共现重建与 00:10-03:30 夜间区）；
 * 管理端可手动触发。文本 = 标题 + 品牌 + 类目名；哈希降级向量（维度不符）拒写。
 */
@Slf4j
@Component
public class ProductIndexJob {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final EmbeddingService embeddingService;
    private final ProductVectorStore vectorStore;

    @Value("${lanlink.search.vector.enabled:true}")
    private boolean enabled;

    public ProductIndexJob(ProductMapper productMapper, CategoryMapper categoryMapper,
                           EmbeddingService embeddingService, ProductVectorStore vectorStore) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    @Scheduled(cron = "0 45 * * * ?")
    public void hourly() {
        reindex();
    }

    /** 全量灌库（同步；商品量千级内秒级完成），返回 {indexed, skipped} */
    public synchronized Map<String, Integer> reindex() {
        Map<String, Integer> out = new HashMap<>();
        int indexed = 0;
        int skipped = 0;
        if (!enabled) {
            out.put("indexed", 0);
            out.put("skipped", -1);
            return out;
        }
        try {
            List<Product> products = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                    .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0));
            List<String> texts = new ArrayList<>();
            for (Product p : products) {
                if (p.getProdId() == null) continue;
                texts.add(textOf(p, catName(p.getCatId())));
            }
            if (texts.isEmpty()) {
                out.put("indexed", 0);
                out.put("skipped", 0);
                return out;
            }
            List<float[]> vectors = embeddingService.embedBatch(texts);
            for (int i = 0; i < products.size() && i < vectors.size(); i++) {
                Product p = products.get(i);
                float[] vec = vectors.get(i);
                // 哈希降级向量（384 维）拒写，防污染语义索引
                if (vec == null || vec.length == EmbeddingService.FALLBACK_DIM) {
                    skipped++;
                    continue;
                }
                if (vectorStore.upsert(p.getProdId(), textOf(p, catName(p.getCatId())), vec)) indexed++;
                else skipped++;
            }
            log.info("[prod-search] reindex done: products={} indexed={} skipped={}", products.size(), indexed, skipped);
        } catch (Exception e) {
            log.warn("[prod-search] reindex failed: {}", e.getMessage());
        }
        out.put("indexed", indexed);
        out.put("skipped", skipped);
        return out;
    }

    private static String textOf(Product p, String catName) {
        StringBuilder sb = new StringBuilder();
        if (p.getTitle() != null) sb.append(p.getTitle());
        if (p.getBrand() != null) sb.append(' ').append(p.getBrand());
        if (catName != null && !catName.isBlank()) sb.append(' ').append(catName);
        return sb.toString().trim();
    }

    private String catName(Long catId) {
        try {
            var c = categoryMapper.selectById(catId);
            return c == null ? "" : c.getName();
        } catch (Exception e) {
            return "";
        }
    }
}
