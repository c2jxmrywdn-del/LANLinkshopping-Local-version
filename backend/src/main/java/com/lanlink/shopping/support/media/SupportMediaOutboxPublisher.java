package com.lanlink.shopping.support.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 解析任务发件箱写入：与媒体状态更新放在同一个事务里。
 *
 * <p>顺序固定为「先写 outbox，再更新媒体状态」：反过来的话，状态已变而 outbox 写失败，
 * 这条媒体就再没有任何待办线索（对账任务按 UPLOADED 未入队又能捞回来，但仍应以 outbox 为准）。
 * {@link TransactionTemplate} 在已有事务上下文时会加入，没有时自建，因此两条路径都是原子的。
 *
 * <p>真正投递由 {@link SupportMediaOutboxWorker} 负责：确认接口不依赖 MQ 可用，
 * MQ 故障只会让解析延后，不会让用户看到上传失败。
 */
@Component
public class SupportMediaOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaOutboxPublisher.class);

    private final SupportMediaRepository repository;
    private final TransactionTemplate transactionTemplate;

    public SupportMediaOutboxPublisher(SupportMediaRepository repository,
                                       TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 确认成功后原子写入待办并回填状态。
     *
     * @return 状态是否由本次调用改写（false 表示已有并发请求完成，调用方可按幂等处理）
     */
    public boolean enqueueUploaded(long mediaId, String mediaNo, long actualSize, String sha256,
                                   String contentType) {
        Boolean updated = transactionTemplate.execute(status -> {
            repository.insertOutbox(mediaId);
            return repository.markUploaded(mediaNo, actualSize, sha256, contentType) == 1;
        });
        boolean ok = Boolean.TRUE.equals(updated);
        if (!ok) {
            log.info("[support-media] 重复确认或状态已推进 mediaNo={}", mediaNo);
        }
        return ok;
    }

    /** 重投（对账任务）：outbox 已存在则重置为待投递 */
    public void republish(long mediaId) {
        repository.insertOutbox(mediaId);
    }
}
