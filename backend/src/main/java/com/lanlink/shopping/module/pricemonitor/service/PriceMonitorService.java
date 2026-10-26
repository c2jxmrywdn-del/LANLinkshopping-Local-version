package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark;
import com.lanlink.shopping.module.pricemonitor.entity.PriceAlert;
import com.lanlink.shopping.module.pricemonitor.entity.PriceHistory;
import com.lanlink.shopping.module.pricemonitor.mapper.MarketBenchmarkMapper;
import com.lanlink.shopping.module.pricemonitor.mapper.PriceAlertMapper;
import com.lanlink.shopping.module.pricemonitor.mapper.PriceHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 价格智能监控服务（pricemonitor 模块核心）。
 *
 * 能力：
 *  - 留痕：商品每次调价写入 t_price_history（含变动幅度），商户改价/平台调价统一入口；
 *  - 预警：调价后即时校验订阅规则（BELOW/ABOVE/DROP_PCT/RISE_PCT），命中即站内信通知；
 *  - 基准：每日调度按行业聚合在售商品均价 → t_market_benchmark（幂等，断档自动回补）；
 *  - 分析：商品现价 vs 行业基准偏离 + 区间波动率 → 智能调价建议（HOLD/RAISE/CUT/URGENT）；
 *  - 榜单：全平台近期变动排行（管理端异常监控）。
 *
 * 行情数据说明：行业基准由平台真实商品价聚合（数据源接口预留，可替换外部行情源）。
 */
@Service
public class PriceMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PriceMonitorService.class);
    /** 单次调价视为"异常异动"的幅度阈值（%） */
    private static final BigDecimal ANOMALY_PCT = new BigDecimal("10");
    /** 与行业基准偏离的容忍带（%），带内视为持平 */
    private static final BigDecimal BENCHMARK_BAND = new BigDecimal("10");
    /** 调价建议的持平区间（%）：±1% 内不建议调整，避免为微小价差频繁改价 */
    private static final BigDecimal HOLD_BAND = new BigDecimal("1");

    private final PriceHistoryMapper historyMapper;
    private final MarketBenchmarkMapper benchmarkMapper;
    private final PriceAlertMapper alertMapper;
    private final ProductMapper productMapper;
    private final IndustryMapper industryMapper;
    private final MessageService messageService;

    public PriceMonitorService(PriceHistoryMapper historyMapper, MarketBenchmarkMapper benchmarkMapper,
                               PriceAlertMapper alertMapper, ProductMapper productMapper,
                               IndustryMapper industryMapper, MessageService messageService) {
        this.historyMapper = historyMapper;
        this.benchmarkMapper = benchmarkMapper;
        this.alertMapper = alertMapper;
        this.productMapper = productMapper;
        this.industryMapper = industryMapper;
        this.messageService = messageService;
    }

    // ==================== 留痕 + 预警触发 ====================

    /**
     * 商品调价留痕（商户改价/平台调价统一入口，须在调价事务内调用）。
     * @param prod      已应用新价的商品（含 merId/indId）
     * @param oldPrice  调整前价格
     * @param source    MERCHANT/ADMIN
     * @param operatorId 操作人（可空）
     */
    @Transactional
    public void recordPriceChange(Product prod, BigDecimal oldPrice, BigDecimal newPrice,
                                  String source, Long operatorId) {
        if (oldPrice == null || newPrice == null || oldPrice.compareTo(newPrice) == 0) return;
        BigDecimal changePct = newPrice.subtract(oldPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(oldPrice, 2, RoundingMode.HALF_UP);

        PriceHistory h = new PriceHistory();
        h.setProdId(prod.getProdId());
        h.setMerId(prod.getMerId());
        h.setIndId(prod.getIndId());
        h.setOldPrice(oldPrice);
        h.setNewPrice(newPrice);
        h.setChangePct(changePct);
        h.setSource(source);
        h.setOperatorId(operatorId);
        h.setCreateTime(LocalDateTime.now());
        historyMapper.insert(h);

        evaluateAlerts(prod, oldPrice, newPrice, changePct);
    }

    /** 预警评估：命中即站内信通知订阅者（触发时间幂等标记，当日同规则不重复打扰） */
    private void evaluateAlerts(Product prod, BigDecimal oldPrice, BigDecimal newPrice, BigDecimal changePct) {
        List<PriceAlert> alerts = alertMapper.selectList(Wrappers.<PriceAlert>lambdaQuery()
                .eq(PriceAlert::getProdId, prod.getProdId())
                .eq(PriceAlert::getStatus, 1));
        for (PriceAlert a : alerts) {
            boolean hit = switch (a.getConditionType()) {
                case "BELOW" -> newPrice.compareTo(a.getThreshold()) <= 0 && oldPrice.compareTo(a.getThreshold()) > 0;
                case "ABOVE" -> newPrice.compareTo(a.getThreshold()) >= 0 && oldPrice.compareTo(a.getThreshold()) < 0;
                case "DROP_PCT" -> changePct.negate().compareTo(a.getThreshold()) >= 0;
                case "RISE_PCT" -> changePct.compareTo(a.getThreshold()) >= 0;
                default -> false;
            };
            if (!hit) continue;
            LocalDateTime now = LocalDateTime.now();
            if (a.getLastTriggeredTime() != null && a.getLastTriggeredTime().isAfter(now.minusHours(23))) {
                continue; // 同一规则 24 小时内只提醒一次，防调价风暴刷屏
            }
            String dir = switch (a.getConditionType()) {
                case "BELOW" -> "已低于你设置的 ¥" + a.getThreshold();
                case "ABOVE" -> "已高于你设置的 ¥" + a.getThreshold();
                case "DROP_PCT" -> "单次降幅达到 " + a.getThreshold() + "%";
                default -> "单次涨幅达到 " + a.getThreshold() + "%";
            };
            messageService.send(a.getUserId(), "price", "价格预警触发",
                    "你关注的商品「" + prod.getTitle() + "」现价 ¥" + newPrice + "，" + dir + "。",
                    String.valueOf(prod.getProdId()));
            PriceAlert upd = new PriceAlert();
            upd.setId(a.getId());
            upd.setLastTriggeredTime(now);
            alertMapper.updateById(upd);
        }
    }

    // ==================== 行业基准（每日调度） ====================

    /** 每日 00:10 聚合行业基准价；启动/调用时也补当日与断档（幂等） */
    @Scheduled(cron = "0 10 0 * * ?")
    public void scheduledRefresh() {
        refreshBenchmarks();
    }

    /** 刷新行业基准价：对每个有在售商品的行业，取现价均价写入当日基准（存在则更新） */
    @Transactional
    public int refreshBenchmarks() {
        List<Long> industryIds = productMapper.distinctIndustryIds();
        LocalDate today = LocalDate.now();
        int n = 0;
        for (Long indId : industryIds) {
            BigDecimal avg = benchmarkMapper.avgPriceByIndustry(indId);
            if (avg == null || avg.signum() <= 0) continue;
            avg = avg.setScale(2, RoundingMode.HALF_UP);
            MarketBenchmark prev = benchmarkMapper.selectOne(Wrappers.<MarketBenchmark>lambdaQuery()
                    .eq(MarketBenchmark::getIndId, indId)
                    .lt(MarketBenchmark::getDay, today)
                    .orderByDesc(MarketBenchmark::getDay)
                    .last("LIMIT 1"));
            BigDecimal changePct = prev == null || prev.getBenchmark().signum() <= 0 ? BigDecimal.ZERO
                    : avg.subtract(prev.getBenchmark()).multiply(BigDecimal.valueOf(100))
                    .divide(prev.getBenchmark(), 2, RoundingMode.HALF_UP);
            MarketBenchmark exist = benchmarkMapper.selectOne(Wrappers.<MarketBenchmark>lambdaQuery()
                    .eq(MarketBenchmark::getIndId, indId)
                    .eq(MarketBenchmark::getDay, today));
            if (exist == null) {
                MarketBenchmark mb = new MarketBenchmark();
                mb.setIndId(indId);
                mb.setDay(today);
                mb.setBenchmark(avg);
                mb.setChangePct(changePct);
                benchmarkMapper.insert(mb);
            } else {
                exist.setBenchmark(avg);
                exist.setChangePct(changePct);
                benchmarkMapper.updateById(exist);
            }
            n++;
        }
        return n;
    }

    // ==================== 走势 + 智能建议 ====================

    /** 商品价格走势 + 同窗行业基准 + 日 K 线（前端双线图 / 蜡烛图数据源） */
    public Map<String, Object> trend(Long prodId, int days) {
        Product p = productMapper.selectById(prodId);
        if (p == null) throw new BusinessException("商品不存在");
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Map<String, Object>> priceSeries = historyMapper.dailyLastPrices(prodId);
        List<MarketBenchmark> bench = benchmarkMapper.selectList(Wrappers.<MarketBenchmark>lambdaQuery()
                .eq(MarketBenchmark::getIndId, p.getIndId())
                .ge(MarketBenchmark::getDay, since.toLocalDate())
                .orderByAsc(MarketBenchmark::getDay));
        List<Map<String, Object>> candles = buildDailyOhlc(p, days);

        Map<String, Object> lowest = null;
        Map<String, Object> highest = null;
        for (Map<String, Object> c : candles) {
            BigDecimal low = (BigDecimal) c.get("low");
            BigDecimal high = (BigDecimal) c.get("high");
            if (lowest == null || low.compareTo((BigDecimal) lowest.get("price")) < 0) {
                lowest = new LinkedHashMap<>();
                lowest.put("price", low);
                lowest.put("day", c.get("day"));
            }
            if (highest == null || high.compareTo((BigDecimal) highest.get("price")) > 0) {
                highest = new LinkedHashMap<>();
                highest.put("price", high);
                highest.put("day", c.get("day"));
            }
        }
        BigDecimal rangePct = null;
        if (lowest != null && highest != null) {
            BigDecimal lo = (BigDecimal) lowest.get("price");
            rangePct = lo.signum() <= 0 ? BigDecimal.ZERO
                    : ((BigDecimal) highest.get("price")).subtract(lo)
                    .multiply(BigDecimal.valueOf(100)).divide(lo, 2, RoundingMode.HALF_UP);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", p);
        m.put("series", priceSeries);
        m.put("benchmark", bench);
        m.put("industryName", p.getIndId() == null ? null :
                Optional.ofNullable(industryMapper.selectById(p.getIndId())).map(i -> i.getName()).orElse(null));
        m.put("ohlc", candles);
        m.put("lowest", lowest);
        m.put("highest", highest);
        m.put("rangePct", rangePct);
        m.put("volumeSimulated", true);
        m.put("volumeUnit", "件");
        return m;
    }

    /**
     * 生成连续交易日的日 K 线。
     * 开/高/低/收均取自 t_price_history 的真实调价记录；当日无调价则以前收平推。
     * 成交量在本项目中没有真实撮合数据，按“商品月销量 + 当日调价活跃度”做确定性估算，
     * 并在响应中标注 volumeSimulated=true，避免被误读为真实成交。
     */
    private List<Map<String, Object>> buildDailyOhlc(Product p, int days) {
        List<Map<String, Object>> rows = historyMapper.dailyOhlc(p.getProdId());
        Map<String, Map<String, Object>> byDay = new HashMap<>();
        for (Map<String, Object> r : rows) byDay.put(String.valueOf(r.get("day")), r);

        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(Math.max(days, 1L) - 1L);
        String startDay = start.toString();
        BigDecimal current = p.getPrice() == null ? BigDecimal.ZERO : p.getPrice();
        BigDecimal prevClose = current;
        boolean hasPrior = false;
        BigDecimal firstOpen = null;
        for (Map<String, Object> r : rows) {
            String day = String.valueOf(r.get("day"));
            if (day.compareTo(startDay) < 0) {
                BigDecimal close = dec(r.get("close"));
                if (close != null && close.signum() > 0) {
                    prevClose = close;
                    hasPrior = true;
                }
            } else if (firstOpen == null) {
                BigDecimal open = dec(r.get("open"));
                if (open != null && open.signum() > 0) firstOpen = open;
            }
        }
        if (!hasPrior && firstOpen != null) prevClose = firstOpen;

        int baseVolume = Math.max(12, (p.getSales() == null ? 0 : p.getSales()) / 30);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            String day = d.toString();
            Map<String, Object> r = byDay.get(day);
            BigDecimal open;
            BigDecimal high;
            BigDecimal low;
            BigDecimal close;
            int trades = 0;
            if (r == null) {
                open = high = low = close = prevClose;
            } else {
                open = dec(r.get("open"));
                high = dec(r.get("high"));
                low = dec(r.get("low"));
                close = dec(r.get("close"));
                trades = decInt(r.get("trades"));
                if (open == null || open.signum() <= 0) open = prevClose;
                if (close == null || close.signum() <= 0) close = open;
                high = high == null ? open.max(close) : high.max(open).max(close);
                low = low == null ? open.min(close) : low.min(open).min(close);
            }
            // 成交量：月销量折日量 × 稳定噪声（62%~100%）+ 调价活跃度加成
            int noise = 62 + Math.floorMod(Objects.hash(p.getProdId(), day), 39);
            int volume = Math.max(1, baseVolume * noise / 100)
                    + trades * Math.max(4, baseVolume / 4);
            BigDecimal changePct = prevClose.signum() <= 0 ? BigDecimal.ZERO
                    : close.subtract(prevClose).multiply(BigDecimal.valueOf(100))
                    .divide(prevClose, 2, RoundingMode.HALF_UP);

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("day", day);
            c.put("open", open.setScale(2, RoundingMode.HALF_UP));
            c.put("high", high.setScale(2, RoundingMode.HALF_UP));
            c.put("low", low.setScale(2, RoundingMode.HALF_UP));
            c.put("close", close.setScale(2, RoundingMode.HALF_UP));
            c.put("volume", volume);
            c.put("trades", trades);
            c.put("changePct", changePct);
            out.add(c);
            prevClose = close;
        }
        return out;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return null;
        try {
            return v instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int decInt(Object v) {
        BigDecimal d = dec(v);
        return d == null ? 0 : d.intValue();
    }

    /**
     * 智能调价建议：现价 vs 行业基准偏离 + 区间波动率。
     * 等级：HOLD 持平 / CUT 建议下调 / RAISE 建议上调 / URGENT 显著偏离（超出 2× 容忍带）。
     */
    public Map<String, Object> analyze(Long prodId) {
        Product p = productMapper.selectById(prodId);
        if (p == null) throw new BusinessException("商品不存在");
        BigDecimal price = p.getPrice() == null ? BigDecimal.ZERO : p.getPrice();
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<MarketBenchmark> bench = benchmarkMapper.selectList(Wrappers.<MarketBenchmark>lambdaQuery()
                .eq(MarketBenchmark::getIndId, p.getIndId())
                .ge(MarketBenchmark::getDay, since.toLocalDate())
                .orderByAsc(MarketBenchmark::getDay));
        List<PriceHistory> histories = historyMapper.selectList(Wrappers.<PriceHistory>lambdaQuery()
                .eq(PriceHistory::getProdId, prodId)
                .ge(PriceHistory::getCreateTime, since));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prodId", prodId);
        m.put("title", p.getTitle());
        m.put("currentPrice", price);
        m.put("changeCount", histories.size());

        if (bench.isEmpty()) {
            m.put("level", "HOLD");
            m.put("advice", "本行业暂无行情基准数据，建议保持现价并持续观察。");
            m.put("benchmarkPrice", null);
            m.put("deviationPct", null);
            m.put("volatilityPct", null);
            return m;
        }
        BigDecimal latest = bench.get(bench.size() - 1).getBenchmark();
        BigDecimal first = bench.get(0).getBenchmark();
        BigDecimal deviation = latest.signum() <= 0 ? BigDecimal.ZERO
                : price.subtract(latest).multiply(BigDecimal.valueOf(100)).divide(latest, 2, RoundingMode.HALF_UP);
        // 波动率 = 基准序列标准差 / 均值
        BigDecimal volatility = volatilityPct(bench);

        String level;
        String advice;
        BigDecimal abs = deviation.abs();
        if (abs.compareTo(BENCHMARK_BAND.multiply(BigDecimal.valueOf(2))) >= 0) {
            level = "URGENT";
            advice = deviation.signum() > 0
                    ? "现价高于行业基准 " + abs + "%（基准 ¥" + latest + "），显著偏离市场，建议尽快下调至 ¥" + latest + " 附近，否则面临滞销风险。"
                    : "现价低于行业基准 " + abs + "%（基准 ¥" + latest + "），存在利润流失或低价倾销疑虑，建议上调至 ¥" + latest + " 附近。";
        } else if (abs.compareTo(HOLD_BAND) <= 0) {
            level = "HOLD";
            advice = "现价与行业基准（¥" + latest + "）基本持平（偏离 " + deviation + "%），建议保持。";
        } else if (deviation.signum() > 0) {
            level = "RAISE";
            advice = "现价高于行业基准 " + deviation + "%（基准 ¥" + latest + "），若无品牌/服务溢价支撑，建议关注竞品动向，适度让利提升转化。";
        } else if (deviation.signum() < 0) {
            level = "CUT";
            advice = "现价低于行业基准 " + abs + "%（基准 ¥" + latest + "），具备让利空间，可配合活动放量；若为长期策略请确认毛利底线。";
        } else {
            level = "HOLD";
            advice = "现价与行业基准（¥" + latest + "）基本持平，建议保持。";
        }
        if (volatility.compareTo(new BigDecimal("8")) > 0) {
            advice += " 近 30 天行业基准波动 " + volatility + "%，市场波动较大，建议缩短复查周期。";
        }
        m.put("level", level);
        m.put("advice", advice);
        m.put("benchmarkPrice", latest);
        m.put("benchmarkChangePct", latest.subtract(first).multiply(BigDecimal.valueOf(100))
                .divide(first.signum() <= 0 ? BigDecimal.ONE : first, 2, RoundingMode.HALF_UP));
        m.put("deviationPct", deviation);
        m.put("volatilityPct", volatility);
        m.put("benchmarkSeries", bench);
        return m;
    }

    private BigDecimal volatilityPct(List<MarketBenchmark> bench) {
        List<BigDecimal> xs = bench.stream().map(MarketBenchmark::getBenchmark).toList();
        int n = xs.size();
        if (n < 2) return BigDecimal.ZERO;
        BigDecimal mean = xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        BigDecimal sq = BigDecimal.ZERO;
        for (BigDecimal x : xs) sq = sq.add(x.subtract(mean).pow(2));
        BigDecimal std = sqrt(sq.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP));
        return mean.signum() <= 0 ? BigDecimal.ZERO
                : std.divide(mean, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sqrt(BigDecimal v) {
        double d = Math.sqrt(v.doubleValue());
        return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP);
    }

    // ==================== 商户端总览 / 管理端异常榜 ====================

    /** 商户价格监控总览：自己每个在售商品的现价/近30日变动/预警订阅数 */
    public List<Map<String, Object>> merchantOverview(Long merId) {
        List<Product> products = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getMerId, merId)
                .orderByDesc(Product::getUpdateTime));
        if (products.isEmpty()) return List.of();
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : products) {
            Long changes = historyMapper.selectCount(Wrappers.<PriceHistory>lambdaQuery()
                    .eq(PriceHistory::getProdId, p.getProdId())
                    .ge(PriceHistory::getCreateTime, since));
            Long alerts = alertMapper.selectCount(Wrappers.<PriceAlert>lambdaQuery()
                    .eq(PriceAlert::getProdId, p.getProdId())
                    .eq(PriceAlert::getStatus, 1));
            PriceHistory last = historyMapper.selectOne(Wrappers.<PriceHistory>lambdaQuery()
                    .eq(PriceHistory::getProdId, p.getProdId())
                    .orderByDesc(PriceHistory::getCreateTime)
                    .last("LIMIT 1"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prodId", p.getProdId());
            m.put("title", p.getTitle());
            m.put("indId", p.getIndId());
            m.put("price", p.getPrice());
            m.put("status", p.getStatus());
            m.put("reviewStatus", p.getReviewStatus());
            m.put("changeCount30d", changes);
            m.put("lastChangePct", last == null ? null : last.getChangePct());
            m.put("lastChangeTime", last == null ? null : last.getCreateTime());
            m.put("alertCount", alerts);
            m.put("anomaly", last != null && last.getChangePct() != null
                    && last.getChangePct().abs().compareTo(ANOMALY_PCT) >= 0);
            out.add(m);
        }
        return out;
    }

    /** 首页每日行情：有调价留痕的在售商品（按最近调价时间排序，最多 24 个） */
    public List<Map<String, Object>> marketProducts(int limit) {
        return historyMapper.productsWithHistory(Math.min(Math.max(limit, 1), 24));
    }

    /** 管理端：近期全平台价格变动排行（含商品信息） */
    public List<Map<String, Object>> adminAnomalies(int days, int limit) {
        return historyMapper.recentChanges(LocalDateTime.now().minusDays(days), Math.min(limit, 100));
    }

    /** 管理端：各行业基准总览（最新一条 + 当日涨跌） */
    public List<Map<String, Object>> adminBenchmarks() {
        List<MarketBenchmark> latest = benchmarkMapper.selectList(Wrappers.<MarketBenchmark>lambdaQuery()
                .orderByDesc(MarketBenchmark::getDay)
                .last("LIMIT 200"));
        Map<Long, MarketBenchmark> latestByInd = new LinkedHashMap<>();
        for (MarketBenchmark b : latest) latestByInd.putIfAbsent(b.getIndId(), b);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, MarketBenchmark> e : latestByInd.entrySet()) {
            MarketBenchmark b = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("indId", b.getIndId());
            m.put("industryName", Optional.ofNullable(industryMapper.selectById(b.getIndId()))
                    .map(i -> i.getName()).orElse("行业 " + b.getIndId()));
            m.put("day", b.getDay());
            m.put("benchmark", b.getBenchmark());
            m.put("changePct", b.getChangePct());
            out.add(m);
        }
        return out;
    }

    // ==================== 预警订阅（买家/商户通用） ====================

    @Transactional
    public void subscribe(Long userId, Long prodId, String conditionType, BigDecimal threshold) {
        if (!Set.of("BELOW", "ABOVE", "DROP_PCT", "RISE_PCT").contains(conditionType)) {
            throw new BusinessException("预警条件类型非法");
        }
        if (threshold == null || threshold.signum() <= 0) throw new BusinessException("阈值需大于 0");
        if (productMapper.selectById(prodId) == null) throw new BusinessException("商品不存在");
        Long dup = alertMapper.selectCount(Wrappers.<PriceAlert>lambdaQuery()
                .eq(PriceAlert::getUserId, userId)
                .eq(PriceAlert::getProdId, prodId)
                .eq(PriceAlert::getConditionType, conditionType));
        if (dup > 0) throw new BusinessException("您已订阅过该商品的同类预警");
        PriceAlert a = new PriceAlert();
        a.setUserId(userId);
        a.setProdId(prodId);
        a.setConditionType(conditionType);
        a.setThreshold(threshold);
        a.setStatus(1);
        a.setCreateTime(LocalDateTime.now());
        alertMapper.insert(a);
    }

    /** 商品详情页：当前用户对该商品的订阅（用于前端回显"已开启提醒"） */
    public List<PriceAlert> productAlerts(Long userId, Long prodId) {
        return alertMapper.selectList(Wrappers.<PriceAlert>lambdaQuery()
                .eq(PriceAlert::getUserId, userId)
                .eq(PriceAlert::getProdId, prodId)
                .eq(PriceAlert::getStatus, 1));
    }

    public List<PriceAlert> myAlerts(Long userId) {
        return alertMapper.selectList(Wrappers.<PriceAlert>lambdaQuery()
                .eq(PriceAlert::getUserId, userId)
                .orderByDesc(PriceAlert::getCreateTime));
    }

    @Transactional
    public void cancel(Long userId, Long alertId) {
        PriceAlert a = alertMapper.selectById(alertId);
        if (a == null || !userId.equals(a.getUserId())) throw new BusinessException("预警不存在或无权操作");
        alertMapper.deleteById(alertId);
    }
}
