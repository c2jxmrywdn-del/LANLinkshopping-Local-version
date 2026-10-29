package com.lanlink.shopping.module.recommend.service;

import com.lanlink.shopping.module.recommend.mapper.RecommendStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 推荐统计物化定时任务：
 *  - 每小时第 15 分：共现矩阵全量重建（近 30 天 session 加权共现 + 余弦归一）；
 *  - 每日 03:30：热度日榜重算（VIEW/CART/PAY 汇总 + Wilson 下界 × 时间衰减，销量作先验）。
 * 数据量小（万级内），全量重算幂等可回滚；Redis 实时增量未启用，物化为唯一权威源。
 */
@Component
public class RecommendMaterializeJob {

    private static final Logger log = LoggerFactory.getLogger(RecommendMaterializeJob.class);

    private final RecommendStatMapper statMapper;

    public RecommendMaterializeJob(RecommendStatMapper statMapper) {
        this.statMapper = statMapper;
    }

    @Scheduled(cron = "0 15 * * * ?")
    public void rebuildCooccur() {
        try {
            long t0 = System.currentTimeMillis();
            statMapper.clearCooccur();
            int rows = statMapper.rebuildCooccurCounts();
            statMapper.normalizeCooccurCosine();
            log.info("[recommend] cooccur rebuilt: {} pairs, {}ms", rows, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("[recommend] cooccur rebuild failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void rebuildHotStat() {
        try {
            long t0 = System.currentTimeMillis();
            statMapper.clearHotStat();
            statMapper.loadHotCounters();
            statMapper.computeHotWilson();
            statMapper.applyHotTimeDecay();
            log.info("[recommend] hot stat rebuilt: {}ms", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("[recommend] hot stat rebuild failed: {}", e.getMessage());
        }
    }
}
