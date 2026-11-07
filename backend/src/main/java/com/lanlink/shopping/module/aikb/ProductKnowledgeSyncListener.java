package com.lanlink.shopping.module.aikb;

import com.lanlink.shopping.integration.event.ProductLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

/**
 * 商品生命周期事件监听器（自动化向量知识库构建核心）。
 *
 * 核心设计：
 * 1. 事务强一致安全：使用 @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)，
 *    确保仅在数据库事务成功提交后才执行向量写入/删除，绝不污染知识库；
 * 2. 算力削峰防 DoS：通过独立有界线程池异步执行向量化，避免商户批量改价或密集审核拖垮前端响应或挤爆 Ollama 显存；
 * 3. 单商品秒级同步：审核通过/改价自动触发 KnowledgeIngestService 增量 upsert，杜绝新商品知识真空。
 */
@Component
public class ProductKnowledgeSyncListener {

    private static final Logger log = LoggerFactory.getLogger(ProductKnowledgeSyncListener.class);

    private final KnowledgeIngestService knowledgeIngestService;
    private final ExecutorService syncExecutor;

    public ProductKnowledgeSyncListener(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
        // 有界异步队列：防止密集调用压垮本地 Embedding 算力 (Anti-DoS)
        this.syncExecutor = new ThreadPoolExecutor(
                1, 2, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "product-aikb-sync");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy()
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductLifecycle(ProductLifecycleEvent event) {
        if (event == null || event.getProdId() == null) return;
        log.info("[aikb-sync] received product lifecycle event: prodId={}, action={}",
                event.getProdId(), event.getAction());

        syncExecutor.execute(() -> {
            try {
                // 事件通道只负责低延迟触发；当前商品状态由 sync 统一判定 upsert/delete，
                // 与 outbox 使用同一把锁和同一语义哈希，避免删除/重灌双通道竞态。
                int chunks = knowledgeIngestService.ingestSingleProduct(event.getProdId());
                log.info("[aikb-sync] product #{} handled action={} chunks={}",
                        event.getProdId(), event.getAction(), chunks);
            } catch (Exception e) {
                log.error("[aikb-sync] error syncing product #{} into vector KB: {}",
                        event.getProdId(), e.getMessage(), e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        try {
            syncExecutor.shutdown();
        } catch (Exception ignored) {}
    }
}
