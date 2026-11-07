package com.lanlink.shopping.module.aikb;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox 消费线程。调度 tick 只投递批次，避免本地批量嵌入阻塞 Spring 的其他定时任务。
 */
@Service
public class KbOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(KbOutboxWorker.class);

    private final KbOutboxService outboxService;
    private final KnowledgeIngestService ingestService;
    private final boolean enabled;
    private final int batchSize;
    private final int leaseMinutes;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kb-outbox-worker");
        t.setDaemon(true);
        return t;
    });

    public KbOutboxWorker(KbOutboxService outboxService,
                          KnowledgeIngestService ingestService,
                          @Value("${AI_KB_OUTBOX_ENABLED:true}") boolean enabled,
                          @Value("${AI_KB_OUTBOX_BATCH_SIZE:5}") int batchSize,
                          @Value("${AI_KB_OUTBOX_LEASE_MINUTES:20}") int leaseMinutes) {
        this.outboxService = outboxService;
        this.ingestService = ingestService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 20));
        this.leaseMinutes = Math.max(5, leaseMinutes);
    }

    @Scheduled(fixedDelayString = "${AI_KB_OUTBOX_POLL_MS:15000}",
               initialDelayString = "${AI_KB_OUTBOX_INITIAL_DELAY_MS:45000}")
    public void tick() {
        if (!enabled || !busy.compareAndSet(false, true)) return;
        try {
            executor.submit(this::processBatch);
        } catch (RejectedExecutionException e) {
            busy.set(false);
        } catch (RuntimeException e) {
            busy.set(false);
            log.warn("[kb-outbox] failed to submit batch: {}", e.getMessage());
        }
    }

    private void processBatch() {
        try {
            int recovered = outboxService.resetStuck(leaseMinutes);
            int cleaned = outboxService.cleanupDone(7);
            if (recovered > 0) log.warn("[kb-outbox] recovered {} stale leases", recovered);
            if (cleaned > 0) log.debug("[kb-outbox] cleaned {} completed rows", cleaned);

            List<KbOutboxService.OutboxRow> rows = outboxService.claimBatch(batchSize);
            for (KbOutboxService.OutboxRow row : rows) {
                process(row);
            }
        } catch (Exception e) {
            log.warn("[kb-outbox] batch poll failed: {}", e.getMessage());
        } finally {
            busy.set(false);
        }
    }

    private void process(KbOutboxService.OutboxRow row) {
        try {
            int chunks = ingestService.syncProductKnowledge(row.prodId());
            outboxService.markDone(row.id());
            log.info("[kb-outbox] prodId={} synced action={} chunks={}",
                    row.prodId(), row.action(), chunks);
        } catch (KbIngestBusyException e) {
            outboxService.markBusy(row.id());
            log.info("[kb-outbox] prodId={} deferred: {}", row.prodId(), e.getMessage());
        } catch (Exception e) {
            boolean permanent = isPermanent(e);
            outboxService.markFailed(row.id(), row.retryCount(), e.getMessage(), permanent);
            log.warn("[kb-outbox] prodId={} sync failed retry={}: {}",
                    row.prodId(), row.retryCount() + 1, e.getMessage());
        }
    }

    private static boolean isPermanent(Exception e) {
        String message = String.valueOf(e.getMessage());
        // 维度不符通常是 Ollama/bge-m3 暂未就绪造成的哈希降级，应重试而不是立即死信。
        return message.contains("embedding batch incomplete")
                || message.contains("embedding missing")
                || message.contains("product not eligible");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
