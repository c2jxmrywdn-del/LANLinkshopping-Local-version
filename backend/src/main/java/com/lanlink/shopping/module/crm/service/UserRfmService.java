package com.lanlink.shopping.module.crm.service;

import com.lanlink.shopping.module.crm.mapper.UserRfmMapper;
import com.lanlink.shopping.support.DbJobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** RFM 用户分层：每日 04:00 物化（避开 00:10-03:30 夜间密集区），只读物化表供管理端与人群包。 */
@Service
public class UserRfmService {

    private static final Logger log = LoggerFactory.getLogger(UserRfmService.class);

    private final UserRfmMapper mapper;
    private final DbJobLock jobLock;

    public UserRfmService(UserRfmMapper mapper, DbJobLock jobLock) {
        this.mapper = mapper;
        this.jobLock = jobLock;
    }

    @Scheduled(cron = "0 0 4 * * ?")
    public void rebuild() {
        // clear→rebuild 非幂等：多实例同时进入会互相清掉半成品，必须跨实例互斥
        jobLock.runExclusive("lanlink:job:rfm-rebuild", () -> {
            try {
                long t0 = System.currentTimeMillis();
                mapper.clear();
                mapper.loadBase();
                mapper.scoreF();
                mapper.scoreM();
                mapper.labelLayers();
                log.info("[rfm] rebuilt: {}ms", System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.warn("[rfm] rebuild failed: {}", e.getMessage());
            }
        });
    }

    public List<Map<String, Object>> layerSummary() {
        return mapper.layerSummary();
    }
}
