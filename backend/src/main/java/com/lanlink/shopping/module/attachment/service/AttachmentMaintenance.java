package com.lanlink.shopping.module.attachment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 挂起件回收：上传后一直没绑定的材料（用户传完就走、或绑定时校验没过）不会自己消失，
 * 而桶是匿名只读，残留一份就多一份泄露面，所以定期置失效并删对象。
 */
@Component
public class AttachmentMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AttachmentMaintenance.class);
    private static final int BATCH_SIZE = 200;

    private final StorageService storage;
    private final AttachmentMapper attachmentMapper;

    public AttachmentMaintenance(StorageService storage, AttachmentMapper attachmentMapper) {
        this.storage = storage;
        this.attachmentMapper = attachmentMapper;
    }

    @Scheduled(fixedDelayString = "${app.attachment.reclaim-interval-ms:1800000}",
            initialDelayString = "${app.attachment.reclaim-initial-delay-ms:60000}")
    public void scheduledReclaim() {
        int retired = reclaim(LocalDateTime.now());
        if (retired > 0) {
            log.info("[attachment] 回收挂起材料 {} 份", retired);
        }
    }

    /**
     * 扫库 + 代码侧二次判定。两个口径都要过：库里按 create_time 筛，代码再按同一个保留期复核，
     * 时钟或时区不一致时宁可漏收一轮，也不能误删刚上传的材料。
     */
    int reclaim(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(AttachmentRetention.PENDING_RETENTION);
        LambdaQueryWrapper<Attachment> query = Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getStatus, "PENDING")
                .lt(Attachment::getCreateTime, cutoff)
                .orderByAsc(Attachment::getAttachmentId);

        Page<Attachment> page = new Page<>(1, BATCH_SIZE);
        page.setSearchCount(false);

        int retired = 0;
        for (Attachment row : attachmentMapper.selectList(page, query)) {
            if (!AttachmentRetention.isReclaimable(row, now)) {
                continue;
            }
            Attachment dead = new Attachment();
            dead.setStatus("EXPIRED");
            int marked = attachmentMapper.update(dead, Wrappers.<Attachment>lambdaUpdate()
                    .eq(Attachment::getAttachNo, row.getAttachNo())
                    .eq(Attachment::getStatus, "PENDING"));
            if (marked == 0) {
                // 扫到行之后有人刚好完成了绑定：条件更新抢输就必须收手，
                // 否则这里会把已交付材料的对象删掉，而且交付件根本不该被回收
                continue;
            }
            try {
                storage.delete(row.getObjectKey());
            } catch (RuntimeException e) {
                // 行已失效即回收完成；对象残留交给运维兜底，否则这一轮会反复重扫同一条。
                // 日志只记单号：桶匿名可读，对象键进日志等于交出一条可下载地址。
                log.warn("[attachment] 挂起件对象删除失败 no={}", row.getAttachNo(), e);
            }
            // 挂起期也可能已经生成了预览，派生物要一起走
            if (row.getThumbKey() != null && !row.getThumbKey().isBlank()) {
                try {
                    storage.delete(row.getThumbKey());
                } catch (RuntimeException e) {
                    log.warn("[attachment] 挂起件缩略图删除失败 no={}", row.getAttachNo(), e);
                }
            }
            retired++;
        }
        return retired;
    }
}
