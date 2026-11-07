package com.lanlink.shopping.module.metrics.service;

import com.lanlink.shopping.module.metrics.mapper.MetricsMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台经营指标看板（通用评估体系的在线指标与护栏指标统一口径）：
 *  - funnel     全站行为漏斗（访问→浏览→点击→加购→支付）+ 逐日 CTR（CLICK_PRODUCT/VIEW_PRODUCT）；
 *  - bySource   分来源浏览/点击/CTR（搜索、推荐位等来源效率对比；推荐位曝光/点击由前端 sourceType=recommend 上报）；
 *  - dailyTrade 交易护栏逐日 GMV/订单数/AOV/付款用户数；
 *  - ads        广告推广汇总（曝光/点击/花费）。
 * 与商户流量中心、推荐物化统计互不依赖：本服务直读原始表，是平台级权威口径。
 */
@Service
public class MetricsService {

    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_DAYS = 90;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final MetricsMapper mapper;

    public MetricsService(MetricsMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> overview(Integer days) {
        int d = norm(days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("funnel", funnel(d));
        out.put("bySource", bySource(d));
        out.put("dailyTrade", dailyTrade(d));
        out.put("ads", ads());
        return out;
    }

    /** 漏斗：总计数 + 逐日明细（缺数日补零，保证窗口内逐日可对账） */
    private Map<String, Object> funnel(int d) {
        Map<String, Long> byType = new HashMap<>();
        for (Map<String, Object> r : mapper.funnelByType(d)) {
            byType.put(String.valueOf(r.get("eventType")), num(r.get("cnt")));
        }
        long visits = byType.getOrDefault("VISIT", 0L);
        long views = byType.getOrDefault("VIEW_PRODUCT", 0L);
        long clicks = byType.getOrDefault("CLICK_PRODUCT", 0L);
        long carts = byType.getOrDefault("ADD_CART", 0L);
        long pays = byType.getOrDefault("PAY", 0L);

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("visits", visits);
        o.put("views", views);
        o.put("clicks", clicks);
        o.put("carts", carts);
        o.put("pays", pays);
        o.put("ctr", rate(clicks, views));
        o.put("viewRate", rate(views, visits));
        o.put("payRate", rate(pays, carts));
        o.put("daily", fillDays(d, mapper.funnelDaily(d), "views", "clicks", "visits", "carts", "pays"));
        return o;
    }

    /** 分来源：浏览/点击/CTR（percentage 两位小数，与 TrafficService.rate 口径一致） */
    private List<Map<String, Object>> bySource(int d) {
        List<Map<String, Object>> rows = mapper.bySource(d);
        List<Map<String, Object>> out = new ArrayList<>();
        long totalViews = 0;
        for (Map<String, Object> r : rows) {
            totalViews += num(r.get("views"));
        }
        for (Map<String, Object> r : rows) {
            long views = num(r.get("views"));
            long clicks = num(r.get("clicks"));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("source", r.get("source"));
            o.put("views", views);
            o.put("clicks", clicks);
            o.put("ctr", rate(clicks, views));
            o.put("viewSharePct", rate(views, totalViews));
            out.add(o);
        }
        return out;
    }

    /** 交易护栏逐日：GMV/订单数/AOV/付款用户数（缺数日补零行） */
    private List<Map<String, Object>> dailyTrade(int d) {
        Map<String, Map<String, Object>> by = new HashMap<>();
        for (Map<String, Object> r : mapper.dailyTrade(d)) {
            by.put(String.valueOf(r.get("date")), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate cur = LocalDate.now().minusDays(d - 1L);
        for (int i = 0; i < d; i++) {
            String k = cur.format(ISO);
            Map<String, Object> r = by.get(k);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("date", k);
            if (r == null) {
                o.put("gmv", BigDecimal.ZERO.setScale(2));
                o.put("orders", 0L);
                o.put("payUsers", 0L);
                o.put("aov", BigDecimal.ZERO.setScale(2));
            } else {
                BigDecimal gmv = dec(r.get("gmv")).setScale(2, RoundingMode.HALF_UP);
                long orders = num(r.get("orders"));
                o.put("gmv", gmv);
                o.put("orders", orders);
                o.put("payUsers", num(r.get("payUsers")));
                o.put("aov", orders > 0 ? gmv.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2));
            }
            out.add(o);
            cur = cur.plusDays(1);
        }
        return out;
    }

    private Map<String, Object> ads() {
        Map<String, Object> r = mapper.adsSummary();
        long impressions = num(r.get("impressions"));
        long clicks = num(r.get("clicks"));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("campaigns", num(r.get("campaigns")));
        o.put("activeCampaigns", num(r.get("activeCampaigns")));
        o.put("impressions", impressions);
        o.put("clicks", clicks);
        o.put("ctr", rate(clicks, impressions));
        o.put("spent", dec(r.get("spent")).setScale(2, RoundingMode.HALF_UP));
        return o;
    }

    /** 窗口内逐日补零对齐（日期为唯一键，其余指标列缺省 0） */
    private List<Map<String, Object>> fillDays(int d, List<Map<String, Object>> rows, String... metricKeys) {
        Map<String, Map<String, Object>> by = new HashMap<>();
        for (Map<String, Object> r : rows) by.put(String.valueOf(r.get("date")), r);
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate cur = LocalDate.now().minusDays(d - 1L);
        for (int i = 0; i < d; i++) {
            String k = cur.format(ISO);
            Map<String, Object> r = by.get(k);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("date", k);
            for (String key : metricKeys) o.put(key, r == null ? 0L : num(r.get(key)));
            if (r != null) o.put("ctr", rate(num(r.get("clicks")), num(r.get("views"))));
            else o.put("ctr", 0.0);
            out.add(o);
            cur = cur.plusDays(1);
        }
        return out;
    }

    private int norm(Integer d) {
        return d == null || d <= 0 ? DEFAULT_DAYS : Math.min(d, MAX_DAYS);
    }

    /** MySQL 8 的 SUM(布尔)/COUNT 经 JDBC 可能返回 Decimal/字符串，统一经字符串解析（同 AbStatService） */
    private static long num(Object o) {
        return o == null ? 0 : (long) Double.parseDouble(String.valueOf(o));
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(o));
    }

    /** 百分比两位小数（口径同 TrafficService.rate） */
    private static double rate(long a, long b) {
        return b == 0 ? 0 : Math.round(a * 10000.0 / b) / 100.0;
    }
}
