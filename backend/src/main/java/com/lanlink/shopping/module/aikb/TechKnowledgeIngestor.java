package com.lanlink.shopping.module.aikb;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechPolicy;
import com.lanlink.shopping.module.techservice.entity.TechPost;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDemandMapper;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechPolicyMapper;
import com.lanlink.shopping.module.techservice.mapper.TechPostMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper;
import com.lanlink.shopping.module.techservice.service.TechKnowledgeComposer;
import com.lanlink.shopping.module.pricemonitor.service.AnalysisSafetyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术服务知识灌库：把 7 类可对外展示的实体写成向量块，供 RAG 引用。
 *
 * <p>三条硬约束：
 * 1) 只灌公开可见的行，且每条文本自带可信标记（演示/未签约/未核验），因为库里的主体大多
 *    是演示种子，混进通用索引就会被模型说成真实合作方；
 * 2) 嵌入不可用时明确失败，不返回「0 块也算成功」——降级哈希向量会被 {@code RedisVectorStore}
 *    按维度拒绝，静默写空会让运营以为已经灌好；
 * 3) 幂等：按表内容指纹跳过未变化的重灌，避免每次重启重复调用嵌入模型。
 */
@Service
public class TechKnowledgeIngestor {

    private static final Logger log = LoggerFactory.getLogger(TechKnowledgeIngestor.class);

    private final TechExpertMapper expertMapper;
    private final TechOrganizationMapper organizationMapper;
    private final TechServiceProductMapper serviceProductMapper;
    private final TechDemandMapper demandMapper;
    private final TechAchievementMapper achievementMapper;
    private final TechPolicyMapper policyMapper;
    private final TechPostMapper postMapper;
    private final TechKnowledgeComposer composer;
    private final EmbeddingService embeddingService;
    private final RedisVectorStore store;

    public TechKnowledgeIngestor(TechExpertMapper expertMapper,
                                 TechOrganizationMapper organizationMapper,
                                 TechServiceProductMapper serviceProductMapper,
                                 TechDemandMapper demandMapper,
                                 TechAchievementMapper achievementMapper,
                                 TechPolicyMapper policyMapper,
                                 TechPostMapper postMapper,
                                 TechKnowledgeComposer composer,
                                 EmbeddingService embeddingService,
                                 RedisVectorStore store) {
        this.expertMapper = expertMapper;
        this.organizationMapper = organizationMapper;
        this.serviceProductMapper = serviceProductMapper;
        this.demandMapper = demandMapper;
        this.achievementMapper = achievementMapper;
        this.policyMapper = policyMapper;
        this.postMapper = postMapper;
        this.composer = composer;
        this.embeddingService = embeddingService;
        this.store = store;
    }

    /** 灌入全部技术服务源，返回每类写入块数。 */
    public int ingestAllTechSources() {
        int total = 0;
        total += ingestSource(TechKnowledgeComposer.SOURCE_EXPERT, expertChunks());
        total += ingestSource(TechKnowledgeComposer.SOURCE_ORGANIZATION, organizationChunks());
        total += ingestSource(TechKnowledgeComposer.SOURCE_SERVICE, serviceChunks());
        total += ingestSource(TechKnowledgeComposer.SOURCE_DEMAND, demandChunks());
        total += ingestSource(TechKnowledgeComposer.SOURCE_ACHIEVEMENT, achievementChunks());
        total += ingestSource(TechKnowledgeComposer.SOURCE_POLICY, policyChunks());
        total += ingestSource(TechKnowledgeComposer.SOURCE_POST, postChunks());
        total += ingestSource(TechKnowledgeComposer.SOURCE_MANUAL, manualChunks());
        if (total > 0) store.invalidateCache();
        log.info("[aikb] tech ingest done: {} chunks", total);
        return total;
    }

    private int ingestSource(String source, List<TechKnowledgeComposer.Chunk> chunks) {
        if (chunks.isEmpty()) {
            // 没有可见数据不等于灌库失败：清掉旧快照，避免下架内容继续被召回
            store.deleteSource(source);
            store.setState(fingerprintKey(source), "");
            return 0;
        }
        String fingerprint = fingerprintOf(chunks);
        String stateKey = fingerprintKey(source);
        List<RedisVectorStore.DocInfo> existing = store.list(source, Integer.MAX_VALUE);
        if (fingerprint.equals(store.getState(stateKey)) && !existing.isEmpty()) {
            log.info("[aikb] tech source {} unchanged ({} chunks), skip re-embedding", source, chunks.size());
            return 0;
        }

        List<String> texts = chunks.stream().map(TechKnowledgeComposer.Chunk::text).toList();
        List<float[]> vectors;
        try {
            vectors = embeddingService.embedBatch(texts);
        } catch (Exception e) {
            throw new IllegalStateException("技术服务知识嵌入失败（" + source + "）：" + e.getMessage(), e);
        }
        int stored = 0;
        List<String> newIds = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float[] v = i < vectors.size() ? vectors.get(i) : null;
            if (v == null || v.length == 0) continue;
            TechKnowledgeComposer.Chunk c = chunks.get(i);
            String id = store.upsert(c.source(), c.refId(), c.title(), c.text(), v);
            if (id != null) {
                newIds.add(id);
                stored++;
            }
        }
        if (stored == 0) {
            // 典型成因：嵌入模型不可用，落到 384 维哈希降级，被向量库按维度全部拒绝
            throw new IllegalStateException("技术服务知识 " + source + " 的 " + chunks.size()
                    + " 个知识块全部写入失败：嵌入模型不可用或维度与索引不一致（当前 "
                    + embeddingService.embedModel() + "）。请勿据此认为已灌库成功。");
        }
        if (stored < chunks.size()) {
            log.warn("[aikb] tech source {} partially stored ({}/{})，保留旧快照", source, stored, chunks.size());
            return stored;
        }
        List<String> stale = existing.stream()
                .map(RedisVectorStore.DocInfo::id)
                .filter(id -> id != null && !newIds.contains(id))
                .toList();
        if (!stale.isEmpty()) store.deleteDocs(stale);
        store.setState(stateKey, fingerprint);
        log.info("[aikb] tech source {} ingested: {} chunks", source, stored);
        return stored;
    }

    private String fingerprintKey(String source) {
        return "tech:" + source + ":fingerprint";
    }

    /** 指纹绑定内容与嵌入模型：模型或维度换了必须重灌，否则新旧向量不在同一空间 */
    private String fingerprintOf(List<TechKnowledgeComposer.Chunk> chunks) {
        StringBuilder sb = new StringBuilder(embeddingService.embedModel()).append('|')
                .append(chunks.size()).append('|');
        for (TechKnowledgeComposer.Chunk c : chunks) sb.append(c.refId()).append('=').append(c.text()).append('\n');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.substring(0, 32);
        } catch (Exception e) {
            return String.valueOf(sb.toString().hashCode());
        }
    }

    // ==================== 各源取数（只取公开可见行） ====================

    private List<TechKnowledgeComposer.Chunk> expertChunks() {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>();
        for (TechExpert e : expertMapper.selectList(Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getStatus, 1).orderByAsc(TechExpert::getExpertId))) {
            out.addAll(protect(composer.fromExpert(e)));
        }
        return out;
    }

    private List<TechKnowledgeComposer.Chunk> organizationChunks() {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>();
        for (TechOrganization o : organizationMapper.selectList(Wrappers.<TechOrganization>lambdaQuery()
                .eq(TechOrganization::getStatus, 1).orderByAsc(TechOrganization::getOrgId))) {
            out.addAll(protect(composer.fromOrganization(o)));
        }
        return out;
    }

    private List<TechKnowledgeComposer.Chunk> serviceChunks() {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>();
        for (TechServiceProduct p : serviceProductMapper.selectList(Wrappers.<TechServiceProduct>lambdaQuery()
                .eq(TechServiceProduct::getStatus, 1).orderByAsc(TechServiceProduct::getServiceId))) {
            out.addAll(protect(composer.fromServiceProduct(p)));
        }
        return out;
    }

    private List<TechKnowledgeComposer.Chunk> demandChunks() {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>();
        for (TechDemand d : demandMapper.selectList(Wrappers.<TechDemand>lambdaQuery()
                .in(TechDemand::getStatus, "MATCHING", "IN_PROGRESS").orderByAsc(TechDemand::getDemandId))) {
            out.addAll(protect(composer.fromDemand(d)));
        }
        return out;
    }

    private List<TechKnowledgeComposer.Chunk> achievementChunks() {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>();
        for (TechAchievement a : achievementMapper.selectList(Wrappers.<TechAchievement>lambdaQuery()
                .in(TechAchievement::getStatus, "LISTED", "MATCHING", "IN_DEAL")
                .orderByAsc(TechAchievement::getAchievementId))) {
            out.addAll(protect(composer.fromAchievement(a)));
        }
        return out;
    }

    private List<TechKnowledgeComposer.Chunk> policyChunks() {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>();
        for (TechPolicy p : policyMapper.selectList(Wrappers.<TechPolicy>lambdaQuery()
                .eq(TechPolicy::getStatus, 1).orderByAsc(TechPolicy::getPolicyId))) {
            out.addAll(protect(composer.fromPolicy(p)));
        }
        return out;
    }

    /** 机制说明与八条服务线边界合成一个源（tech-manual），便于单独重灌与调权 */
    private List<TechKnowledgeComposer.Chunk> manualChunks() {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>();
        out.addAll(protect(composer.manualChunks()));
        out.addAll(protect(composer.boundaryChunks()));
        return out;
    }

    private List<TechKnowledgeComposer.Chunk> postChunks() {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>();
        for (TechPost post : postMapper.selectList(Wrappers.<TechPost>lambdaQuery()
                .eq(TechPost::getStatus, 1).orderByAsc(TechPost::getPostId))) {
            out.addAll(protect(composer.fromPost(post)));
        }
        return out;
    }

    /** 自由文本入库前过滤指令注入与有害内容（与 news 源同一道处理） */
    private List<TechKnowledgeComposer.Chunk> protect(List<TechKnowledgeComposer.Chunk> chunks) {
        List<TechKnowledgeComposer.Chunk> out = new ArrayList<>(chunks.size());
        for (TechKnowledgeComposer.Chunk c : chunks) {
            String cleaned = AnalysisSafetyFilter.sanitize(c.text());
            if (cleaned == null || cleaned.isBlank()) continue;
            out.add(new TechKnowledgeComposer.Chunk(c.source(), c.refId(), c.title(), cleaned));
        }
        return out;
    }
}
