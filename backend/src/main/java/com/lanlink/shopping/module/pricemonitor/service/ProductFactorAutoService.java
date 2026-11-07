package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 商品五维因子自动分析（面向"未来发布的商品"的自主分析链）：
 *
 * <ol>
 *   <li>每小时 :50 扫描"无分析快照"的在售商品——未来发布/审核通过的商品自动纳入，无需人工跑批；</li>
 *   <li>每个商品：本地五维因子（PriceMonitorAiService.factors）+ 自主网络检索（类目+品牌行情，
 *       经有害信息过滤）→ 快照落库 t_product_factor_auto；</li>
 *   <li>商户内容进入 AI 上下文前经 {@link AnalysisSafetyFilter} 注入净化与有害标记；</li>
 *   <li>管理端可手动全量重建（发布/导入后立即可见）；快照经 GET /price-monitor/auto-profile/{prodId} 查询。</li>
 * </ol>
 */
@Slf4j
@Service
public class ProductFactorAutoService {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final PriceMonitorAiService aiService;
    private final MarketWebSearchService webSearch;
    private final AnalysisSafetyFilter safetyFilter;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final ObjectMapper OM = new ObjectMapper();

    @Value("${lanlink.factor-auto.enabled:true}")
    private boolean enabled;

    /** 启动后延迟补跑间隔（ms）：默认 90s，等服务与依赖（MySQL/Redis）就绪后补齐重启期间缺失的快照 */
    @Value("${lanlink.factor-auto.bootstrap-delay-ms:90000}")
    private long bootstrapDelayMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 事件驱动即时分析的单线程执行器（有界队列防密集发布压垮网络检索） */
    private final java.util.concurrent.ExecutorService asyncExecutor =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(200),
                    r -> { Thread t = new Thread(r, "factor-auto-event"); t.setDaemon(true); return t; },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    /** 启动补跑线程引用（仅用于停机时中断睡眠，业务本体走 daemon 模式不阻塞 JVM 退出） */
    private volatile Thread bootstrapThread;

    @jakarta.annotation.PreDestroy
    void shutdownExecutor() {
        if (bootstrapThread != null) bootstrapThread.interrupt();
        asyncExecutor.shutdownNow();
    }

    public ProductFactorAutoService(ProductMapper productMapper, CategoryMapper categoryMapper,
                                    PriceMonitorAiService aiService, MarketWebSearchService webSearch,
                                    AnalysisSafetyFilter safetyFilter,
                                    org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.aiService = aiService;
        this.webSearch = webSearch;
        this.safetyFilter = safetyFilter;
        this.jdbc = jdbc;
    }

    /** 每小时 :50：纳入未来发布商品（避开 :45 向量灌库与夜间密集区） */
    @Scheduled(cron = "0 50 * * * ?")
    public void hourly() {
        analyzeMissing();
    }

    /**
     * 启动后延迟补跑（daemon 线程，不阻塞启动）：重启期间产生的"无快照商品"尽快补齐，
     * 频繁重启 + 检索降级场景不再等整点 :50。enabled 开关与 running 守卫照走：
     * 被整点任务占用时自然返回 skipped=-2，不产生并发重算。
     */
    @jakarta.annotation.PostConstruct
    void scheduleBootstrapCatchUp() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(Math.max(0L, bootstrapDelayMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Map<String, Integer> r = analyzeMissing();
                log.info("[factor-auto] bootstrap catch-up done: analyzed={} skipped={}",
                        r.get("analyzed"), r.get("skipped"));
            } catch (Exception e) {
                log.warn("[factor-auto] bootstrap catch-up failed: {}", e.getMessage());
            }
        }, "factor-auto-bootstrap");
        bootstrapThread = t;
        t.setDaemon(true);
        t.start();
    }

    /** 分析缺失的在售商品 + 过期快照重算（24h 切换，每轮上限 40）；返回 {analyzed, skipped} */
    public synchronized Map<String, Integer> analyzeMissing() {
        Map<String, Integer> out = new LinkedHashMap<>();
        int analyzed = 0;
        if (!enabled) { out.put("analyzed", 0); out.put("skipped", -1); return out; }
        if (!running.compareAndSet(false, true)) {
            out.put("analyzed", 0); out.put("skipped", -2);
            return out;
        }
        try {
            // 隔离自愈：fail_count>=3 且 24h 内无新失败的隔离商品冷却复位、重新纳入扫描，
            // 成功后由 analyzeOneSafe 清记录（真错误仍会再次失败并重新计数——
            // 防"3 次瞬时网络抖动即永久踢出快照链且无法自愈"的隐患）
            jdbc.update("UPDATE t_product_factor_auto_fail SET fail_count=0 " +
                    "WHERE fail_count>=3 AND last_fail_at < DATE_SUB(NOW(), INTERVAL 24 HOUR)");
            // 第一轮：无快照商品（未来发布商品最迟每小时 :50 纳入）；每轮上限 100 防
            // "首轮无上限 × 检索降级（每商品最长 12s）"拖穿整点周期，余量靠下一轮滚动补齐
            List<Product> todo = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                    .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0)
                    .notInSql(Product::getProdId, "SELECT prod_id FROM t_product_factor_auto")
                    .notInSql(Product::getProdId, "SELECT prod_id FROM t_product_factor_auto_fail WHERE fail_count>=3")
                    .orderByAsc(Product::getProdId)
                    .last("LIMIT 100"));
            for (Product p : todo) {
                if (analyzeOneSafe(p.getProdId())) analyzed++;
            }
            // 第二轮：快照过期重算（>24h），每日滚动刷新保持资讯证据鲜活；每轮上限 40 防风暴
            List<Long> stale = jdbc.queryForList(
                    "SELECT prod_id FROM t_product_factor_auto WHERE generated_at < DATE_SUB(NOW(), INTERVAL 24 HOUR) " +
                            "AND prod_id NOT IN (SELECT prod_id FROM t_product_factor_auto_fail WHERE fail_count>=3) " +
                            "ORDER BY generated_at ASC LIMIT 40", Long.class);
            int refreshed = 0;
            for (Long pid : stale) {
                if (analyzeOneSafe(pid)) refreshed++;
            }
            log.info("[factor-auto] stale refresh: candidates={} refreshed={}", stale.size(), refreshed);
            log.info("[factor-auto] scan summary: missingAnalyzed={} staleRefreshed={}", analyzed, refreshed);
        } catch (Exception e) {
            log.warn("[factor-auto] scan failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
        out.put("analyzed", analyzed);
        out.put("skipped", 0);
        return out;
    }

    /** 全量重建（管理端触发）：清快照与失败隔离后重算所有在售商品 */
    public synchronized Map<String, Integer> rebuildAll() {
        jdbc.update("DELETE FROM t_product_factor_auto");
        jdbc.update("DELETE FROM t_product_factor_auto_fail");
        return analyzeMissing();
    }

    /**
     * 全量重建（管理端触发，异步）：daemon 线程执行 {@link #rebuildAll()}，HTTP 线程立即返回，
     * 避免全量重算在检索降级场景（每商品最长 12s）阻塞请求线程数十分钟。并发防护复用既有机制：
     * rebuildAll/analyzeMissing 均为 synchronized 串行，且 analyzeMissing 内 running CAS 守卫照走。
     * 返回 {analyzed:0, skipped:-3}（-3=已受理异步执行），重建进度经快照索引端点观测。
     */
    public Map<String, Integer> rebuildAllAsync() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("analyzed", 0);
        out.put("skipped", -3);
        Thread t = new Thread(() -> {
            try {
                rebuildAll();
                log.info("[factor-auto] async rebuild done: snapshotCount={}", snapshotCount());
            } catch (Exception e) {
                log.warn("[factor-auto] async rebuild failed: {}", e.getMessage());
            }
        }, "factor-auto-rebuild");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /** 失败隔离包装：成功清失败记录；失败计数+1，连续 3 次后由扫描排除（防无限重试）。管理端单商品重算也走此方法。 */
    public boolean analyzeOneSafe(Long prodId) {
        try {
            analyzeOne(prodId);
            jdbc.update("DELETE FROM t_product_factor_auto_fail WHERE prod_id=?", prodId);
            return true;
        } catch (Exception e) {
            try {
                jdbc.update("INSERT INTO t_product_factor_auto_fail(prod_id, fail_count, last_fail_reason, last_fail_at) " +
                                "VALUES(?,1,?,NOW()) ON DUPLICATE KEY UPDATE fail_count=fail_count+1, " +
                                "last_fail_reason=VALUES(last_fail_reason), last_fail_at=NOW()",
                        prodId, String.valueOf(e.getMessage()).substring(0, Math.min(String.valueOf(e.getMessage()).length(), 490)));
            } catch (Exception ignore) { }
            log.warn("[factor-auto] prod {} failed: {}", prodId, e.getMessage());
            return false;
        }
    }

    /** 事件驱动的即时分析（商品上架/过审后由 Listener 调用；独立单线程，失败静默） */
    public void analyzeAsync(Long prodId) {
        asyncExecutor.submit(() -> {
            try {
                if (analyzeOneSafe(prodId)) {
                    log.info("[factor-auto] event-driven analysis done for prod {}", prodId);
                }
            } catch (Exception ignore) { }
        });
    }

    /** 快照索引（公开：商品列表"已分析"徽标；不含 payload，379 行约 20KB） */
    public List<Map<String, Object>> indexOfAll() {
        return jdbc.queryForList(
                "SELECT f.prod_id prodId, f.web_info_count webInfoCount, f.generated_at generatedAt " +
                        "FROM t_product_factor_auto f ORDER BY f.generated_at DESC");
    }

    /** 失败隔离清单（管理端） */
    public List<Map<String, Object>> failedList() {
        return jdbc.queryForList("SELECT * FROM t_product_factor_auto_fail ORDER BY last_fail_at DESC");
    }

    /** 清除某商品的快照（下架/驳回/删除时调用：旧数据不可信，待重新过审后再分析） */
    public void clearSnapshot(Long prodId) {
        try {
            jdbc.update("DELETE FROM t_product_factor_auto WHERE prod_id=?", prodId);
            jdbc.update("DELETE FROM t_product_factor_auto_fail WHERE prod_id=?", prodId);
            log.info("[factor-auto] snapshot cleared for prod {}", prodId);
        } catch (Exception e) {
            log.warn("[factor-auto] clear snapshot failed for prod {}: {}", prodId, e.getMessage());
        }
    }

    /** 分析并落库单个商品（本地五维 + 自主网络检索 + 有害过滤 + 注入净化） */
    public void analyzeOne(Long prodId) {
        Product p = productMapper.selectById(prodId);
        if (p == null || p.getStatus() == null || p.getStatus() != 1) return;

        // 商户内容安全筛选：注入净化；有害内容只标记不阻断（治理走商户风控）
        var screen = safetyFilter.screenProductText(p.getTitle(), p.getDetail());

        // 本地五维因子（资讯匹配 + 价格行为，确定性计算）
        Map<String, Object> factors = aiService.factors(prodId, "zh");

        // 自主网络检索：类目名行情资讯（虚构品牌名不进搜索词，防相关性污染），经有害过滤
        String catName = catName(p.getCatId());
        // 网络检索属瞬时外部依赖：MarketWebSearchService 契约内任何失败返回空列表，此处再兜一层
        // try/catch 防未来契约破坏——检索失败绝不阻断快照落库，本地五维因子照常产出，
        // 并以 degraded/degradedReason 标注给展示端（前端显示「网络资讯暂缺（降级）」）
        boolean degraded = false;
        String degradedReason = "";
        List<MarketWebSearchService.WebInfoItem> webInfo;
        try {
            webInfo = webSearch.searchMarketInfo(catName, 5);
        } catch (Exception e) {
            degraded = true;
            degradedReason = String.valueOf(e.getMessage());
            webInfo = List.of();
            log.warn("[factor-auto] prod {} web search degraded: {}", prodId, degradedReason);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prodId", prodId);
        payload.put("title", screen.get("title"));
        payload.put("categoryName", catName);
        payload.put("factors", factors.get("factors"));
        payload.put("sentimentIndex", factors.get("sentimentIndex"));
        payload.put("dominant", factors.get("dominant"));
        payload.put("summary", factors.get("summary"));
        payload.put("systematicAnalysis", factors.get("systematicAnalysis"));
        payload.put("contentSafety", Map.of(
                "clean", screen.get("clean"),
                "reason", screen.get("reason") == null ? "" : screen.get("reason")));
        payload.put("webInfo", webInfo);
        if (degraded) {
            payload.put("degraded", true);
            payload.put("degradedReason", degradedReason.length() > 200
                    ? degradedReason.substring(0, 200) : degradedReason);
        }
        payload.put("generatedAt", LocalDateTime.now().toString());

        jdbc.update("INSERT INTO t_product_factor_auto(prod_id, payload, web_info_count, generated_at) " +
                        "VALUES(?,?,?,NOW()) ON DUPLICATE KEY UPDATE payload=VALUES(payload), " +
                        "web_info_count=VALUES(web_info_count), generated_at=NOW()",
                prodId, writeJson(payload), webInfo.size());
    }

    /** 查询某商品的自主分析快照；无则 null */
    public Map<String, Object> profileOf(Long prodId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT payload, web_info_count, generated_at FROM t_product_factor_auto WHERE prod_id=?",
                prodId);
        if (rows.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prodId", prodId);
        out.put("payload", readJson(String.valueOf(rows.get(0).get("payload"))));
        out.put("webInfoCount", rows.get(0).get("web_info_count"));
        out.put("generatedAt", rows.get(0).get("generated_at"));
        return out;
    }

    /** 存量快照数量（管理端观测） */
    public long snapshotCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM t_product_factor_auto", Long.class);
        return n == null ? 0 : n;
    }

    private static String writeJson(Object o) {
        try { return OM.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    private static JsonNode readJson(String json) {
        try { return OM.readTree(json); } catch (Exception e) { return null; }
    }

    private String catName(Long catId) {
        if (catId == null) return "";
        try {
            var c = categoryMapper.selectById(catId);
            return c == null ? "" : c.getName();
        } catch (Exception e) {
            return "";
        }
    }

    private static String titleHead(String title) {
        String t = title == null ? "" : title.trim();
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }
}
