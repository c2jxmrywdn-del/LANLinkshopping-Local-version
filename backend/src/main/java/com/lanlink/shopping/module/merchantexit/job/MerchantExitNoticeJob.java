package com.lanlink.shopping.module.merchantexit.job;

import com.lanlink.shopping.module.merchantexit.service.MerchantExitQueryService;
import com.lanlink.shopping.module.merchantexit.service.MerchantExitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 公示到期扫表：PUBLIC_NOTICE 且 notice_end_time<=NOW() → FROZEN（逐商户 CAS，天然幂等）。 */
@Component
public class MerchantExitNoticeJob {

    private static final Logger log = LoggerFactory.getLogger(MerchantExitNoticeJob.class);

    private final MerchantExitQueryService queryService;
    private final MerchantExitService exitService;

    public MerchantExitNoticeJob(MerchantExitQueryService queryService, MerchantExitService exitService) {
        this.queryService = queryService;
        this.exitService = exitService;
    }

    @Scheduled(fixedDelayString = "${app.merchant-exit.poll-ms:3600000}")
    public void run() {
        int frozen = freezeDue();
        if (frozen > 0) {
            log.info("[merchant-exit] 公示期满转清算 {} 家", frozen);
        }
    }

    public int freezeDue() {
        int n = 0;
        for (Long merId : queryService.dueMerchantIds()) {
            try {
                if (exitService.freezeDue(merId)) {
                    n++;
                }
            } catch (RuntimeException e) {
                // 单商户失败（如站内信写入异常）不得中断整轮扫表，下一轮会重试
                log.warn("[merchant-exit] 商户 {} 公示到期转清算失败，跳过本轮", merId, e);
            }
        }
        return n;
    }
}
