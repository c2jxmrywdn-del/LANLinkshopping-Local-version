package com.lanlink.shopping.module.crm.service;

import com.lanlink.shopping.module.crm.mapper.UserRfmMapper;
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

    public UserRfmService(UserRfmMapper mapper) {
        this.mapper = mapper;
    }

    @Scheduled(cron = "0 0 4 * * ?")
    public void rebuild() {
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
    }

    public List<Map<String, Object>> layerSummary() {
        return mapper.layerSummary();
    }
}
