package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保留期任务（每天 03:00）：
 * <ol>
 *   <li>到 expires_at 仍不是 EXPIRED 的媒体 → 置 EXPIRED（软删，立即从用户视图消失）；</li>
 *   <li>EXPIRED 已满 7 天 → 删除 S3 对象与行（硬删）。</li>
 * </ol>
 *
 * <p>软删与硬删分开两层：先给用户「已过期」的确定性反馈，再留 7 天窗口给对账/审计/误删恢复，
 * 与 MinIO 侧 35 天 ILM 形成第二道保险（对象删失败时桶不会无限增长）。
 */
@Component
public class SupportMediaRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaRetentionJob.class);

    /** 软删后保留 7 天才物理删除 */
    static final int PURGE_GRACE_DAYS = 7;
    private static final int BATCH_SIZE = 200;

    private final SupportMediaRepository repository;
    private final StorageProperties storageProps;
    private final S3Client s3;

    /** 存储关闭时不创建 S3Client Bean，注入 null：软删照做，硬删只清库 */
    public SupportMediaRetentionJob(SupportMediaRepository repository, StorageProperties storageProps,
                                    @Nullable S3Client s3) {
        this.repository = repository;
        this.storageProps = storageProps;
        this.s3 = s3;
    }

    @Scheduled(cron = "${app.support-media.retention-cron:0 0 3 * * ?}")
    public void scheduledRetention() {
        int softDeleted = softDeleteExpired();
        int purged = purgeExpired();
        if (softDeleted > 0 || purged > 0) {
            log.info("[support-media] 保留期任务：软删 {} 条，物理清理 {} 条", softDeleted, purged);
        }
    }

    int softDeleteExpired() {
        List<SupportMediaRepository.Row> due = repository.findDueForSoftDelete(LocalDateTime.now(), BATCH_SIZE);
        int count = 0;
        for (SupportMediaRepository.Row row : due) {
            count += repository.markExpiredById(row.id(), "超过保留期");
        }
        return count;
    }

    int purgeExpired() {
        List<SupportMediaRepository.Row> due =
                repository.findPurgeableExpired(LocalDateTime.now().minusDays(PURGE_GRACE_DAYS), BATCH_SIZE);
        int count = 0;
        for (SupportMediaRepository.Row row : due) {
            if (!deleteObject(row)) {
                continue;
            }
            repository.deleteMedia(row.id());
            count++;
        }
        return count;
    }

    /** 对象删除失败不删行：行是唯一还能定位到这个对象的线索，留着明天再试 */
    private boolean deleteObject(SupportMediaRepository.Row row) {
        if (s3 == null) {
            return true;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(storageProps.getSupportBucket())
                    .key(row.objectKey())
                    .build());
            return true;
        } catch (RuntimeException e) {
            log.warn("[support-media] 删除对象失败，保留行待下次重试 mediaNo={} err={}",
                    row.mediaNo(), e.getMessage());
            return false;
        }
    }
}
