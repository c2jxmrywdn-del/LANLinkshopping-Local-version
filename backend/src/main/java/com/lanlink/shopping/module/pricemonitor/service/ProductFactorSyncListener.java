package com.lanlink.shopping.module.pricemonitor.service;

import com.lanlink.shopping.integration.event.ProductLifecycleEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

/**
 * 商品五维因子自动分析监听器（与向量知识库 ProductKnowledgeSyncListener 同事件源）：
 *
 * <ul>
 *   <li>APPROVED / ON_SHELF / PRICE_UPDATED → 事务提交后异步触发单商品自主分析（消除最长 1 小时的整点等待）；</li>
 *   <li>REJECTED / OFF_SHELF / DELETED / CONTENT_UPDATED → 清除旧快照（旧数据不可信，待重新过审后再分析）。</li>
 * </ul>
 *
 * <p>有界队列防密集发布压垮网络检索（DiscardPolicy：丢弃即由下个整点任务兜底）。
 */
@Slf4j
@Component
public class ProductFactorSyncListener {

    private final ProductFactorAutoService factorAutoService;
    private final ExecutorService syncExecutor;

    public ProductFactorSyncListener(ProductFactorAutoService factorAutoService) {
        this.factorAutoService = factorAutoService;
        this.syncExecutor = new ThreadPoolExecutor(
                1, 2, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> { Thread t = new Thread(r, "product-factor-sync"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @PreDestroy
    void shutdown() { syncExecutor.shutdownNow(); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductLifecycle(ProductLifecycleEvent event) {
        if (event == null || event.getProdId() == null) return;
        switch (event.getAction()) {
            case APPROVED, ON_SHELF, PRICE_UPDATED ->
                syncExecutor.submit(() -> factorAutoService.analyzeAsync(event.getProdId()));
            case REJECTED, OFF_SHELF, DELETED ->
                syncExecutor.submit(() -> factorAutoService.clearSnapshot(event.getProdId()));
            default -> { }
        }
    }
}
