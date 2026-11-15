package com.lanlink.shopping.support.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账任务（每 5 分钟）：
 * <ol>
 *   <li>INIT 超过 15 分钟仍未被确认 → 置 EXPIRED 并释放配额预占；</li>
 *   <li>UPLOADED 超过 10 分钟仍未进队列 → 重投 outbox（MQ 抖动、进程在确认与投递之间崩溃）。</li>
 * </ol>
 *
 * <p>两段阈值都大于预签名有效期（10 分钟）：给用户留足上传时间，同时确保悬空行不会无限累积。
 */
@Component
public class SupportMediaReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaReconcileJob.class);

    /** INIT 悬空阈值：预签名 10 分钟 + 5 分钟上传缓冲 */
    static final int STALE_INIT_MINUTES = 15;
    /** UPLOADED 未入队阈值：正常确认后 5 秒内就会入队，10 分钟即视为异常 */
    static final int STALE_UPLOADED_MINUTES = 10;
    private static final int BATCH_SIZE = 100;

    private final SupportMediaRepository repository;
    private final SupportMediaQuotaService quota;

    public SupportMediaReconcileJob(SupportMediaRepository repository, SupportMediaQuotaService quota) {
        this.repository = repository;
        this.quota = quota;
    }

    @Scheduled(fixedDelayString = "${app.support-media.reconcile-interval-ms:300000}",
            initialDelayString = "${app.support-media.reconcile-initial-delay-ms:60000}")
    public void scheduledReconcile() {
        int expired = expireStaleInit();
        int republished = republishStaleUploaded();
        if (expired > 0 || republished > 0) {
            log.info("[support-media] 对账完成：过期 INIT {} 条，重投 UPLOADED {} 条", expired, republished);
        }
    }

    int expireStaleInit() {
        LocalDateTime before = LocalDateTime.now().minusMinutes(STALE_INIT_MINUTES);
        List<SupportMediaRepository.Row> stale = repository.findExpiredInit(before, BATCH_SIZE);
        int expired = 0;
        for (SupportMediaRepository.Row row : stale) {
            // 只有 CAS 成功的那次才释放配额，避免重复释放把别人的计数减掉
            if (repository.markExpiredById(row.id(), "超时未确认上传") == 1) {
                quota.releaseByMediaNo(row.mediaNo());
                expired++;
            }
        }
        return expired;
    }

    int republishStaleUploaded() {
        LocalDateTime before = LocalDateTime.now().minusMinutes(STALE_UPLOADED_MINUTES);
        List<Long> ids = repository.findUploadedNotEnqueued(before, BATCH_SIZE);
        for (Long id : ids) {
            repository.insertOutbox(id);
        }
        return ids.size();
    }
}
