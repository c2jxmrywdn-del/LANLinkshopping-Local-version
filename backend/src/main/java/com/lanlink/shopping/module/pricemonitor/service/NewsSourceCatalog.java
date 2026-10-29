package com.lanlink.shopping.module.pricemonitor.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 市场资讯来源目录（每日资讯"导向信息源头网页"的数据基础）。
 *
 * <p>t_market_news 由平台按行业资讯快照式灌入，部分条目没有原文链接（url 为空），
 * 部分来源是平台自产（LANLink 数据 / LANLink 调研 / 平台公告）。为了让每条资讯都能
 * "点得开、看得到源头"，这里维护：
 * <ul>
 *   <li>来源名称 → 官方站点/频道页（真实可访问的行业权威站点）；</li>
 *   <li>平台自产来源 → 站内价格情报台路径；</li>
 *   <li>兜底必应资讯检索链接（按标题 + 来源检索原文）。</li>
 * </ul>
 * 该目录是纯静态映射（无网络调用），便于离线演示；新增来源时在这里补一行即可。
 */
public final class NewsSourceCatalog {

    /** 平台自产资讯的站内落地页（前端 router.push，不新开外部页签）。 */
    public static final String INTERNAL_PATH = "/price-monitor";

    /** 来源名称 → 官方站点/频道页。未收录的来源返回 null（走检索兜底）。 */
    private static final Map<String, String> SOURCE_HOME;

    /** 平台自产来源（站内新闻，不导向外部站点）。 */
    private static final Map<String, String> INTERNAL_SOURCES;

    static {
        Map<String, String> home = new LinkedHashMap<>();
        // ---- 行业权威站点（按仓库内市场资讯种子数据的来源名覆盖） ----
        home.put("中国建材报", "https://www.cbminfo.com/");
        home.put("中国建材信息总网", "https://www.cbminfo.com/");
        home.put("中国水泥网", "https://www.dcement.com/");
        home.put("数字水泥网", "https://www.dcement.com/");
        home.put("中国水泥协会", "https://www.dcement.com/");
        home.put("钢铁资讯", "http://www.csteelnews.com/");
        home.put("中国钢铁新闻网", "http://www.csteelnews.com/");
        home.put("中国钢铁工业协会", "http://www.chinaisa.org.cn/");
        home.put("有色行情", "https://www.smm.cn/");
        home.put("有色金属网", "https://www.smm.cn/");
        home.put("上海有色网", "https://www.smm.cn/");
        home.put("中国有色金属工业协会", "https://www.chinania.org.cn/");
        home.put("电子供应链周刊", "https://www.cena.com.cn/");
        home.put("中国电子报", "https://www.cena.com.cn/");
        home.put("纺织信息网", "http://www.ctei.cn/");
        home.put("中国纺织经济信息网", "http://www.ctei.cn/");
        home.put("包装产业网", "http://www.chinapack.org.cn/");
        home.put("中国包装联合会", "http://www.chinapack.org.cn/");
        home.put("新能源观察", "https://guangfu.bjx.com.cn/");
        home.put("新能源材料", "https://battery.bjx.com.cn/");
        home.put("北极星电力网", "https://www.bjx.com.cn/");
        home.put("化工市场", "https://www.chemnews.com.cn/");
        home.put("中国化工信息网", "https://www.chemnews.com.cn/");
        home.put("中国石油和化学工业联合会", "http://www.cpcif.org.cn/");
        home.put("塑料工业", "http://www.cppia.com.cn/");
        home.put("机械工业报", "https://www.cmif.org.cn/");
        home.put("中国机械工业联合会", "https://www.cmif.org.cn/");
        home.put("行业观察", "https://www.cinn.cn/");
        home.put("中国工业新闻网", "https://www.cinn.cn/");
        home.put("政策速递", "https://www.gov.cn/");
        home.put("中国政府网", "https://www.gov.cn/");
        home.put("宏观数据", "https://www.stats.gov.cn/");
        home.put("国家统计局", "https://www.stats.gov.cn/");
        home.put("环保观察", "https://www.mee.gov.cn/");
        home.put("生态环境部", "https://www.mee.gov.cn/");
        home.put("交通发布", "https://www.mot.gov.cn/");
        home.put("交通运输部", "https://www.mot.gov.cn/");
        home.put("航运日报", "https://www.sse.net.cn/");
        home.put("上海航运交易所", "https://www.sse.net.cn/");
        home.put("外汇观察", "https://www.safe.gov.cn/");
        home.put("国家外汇管理局", "https://www.safe.gov.cn/");
        home.put("外贸政策观察", "http://www.mofcom.gov.cn/");
        home.put("商务部", "http://www.mofcom.gov.cn/");
        home.put("能源参考", "http://www.nea.gov.cn/");
        home.put("国家能源局", "http://www.nea.gov.cn/");
        home.put("中国物流与采购联合会", "http://www.chinawuliu.com.cn/");
        SOURCE_HOME = Map.copyOf(home);

        Map<String, String> internal = new LinkedHashMap<>();
        internal.put("平台公告", INTERNAL_PATH);
        internal.put("LANLink 数据", INTERNAL_PATH);
        internal.put("LANLink 调研", INTERNAL_PATH);
        internal.put("LANLink", INTERNAL_PATH);
        INTERNAL_SOURCES = Map.copyOf(internal);
    }

    private NewsSourceCatalog() {
    }

    /** 来源名称 → 官方站点 URL；未收录返回 null。 */
    public static String homeOf(String source) {
        if (source == null) return null;
        return SOURCE_HOME.get(source.trim());
    }

    /** 是否平台自产来源（站内资讯）。 */
    public static boolean isInternal(String source) {
        if (source == null) return false;
        String s = source.trim();
        if (INTERNAL_SOURCES.containsKey(s)) return true;
        String upper = s.toUpperCase(Locale.ROOT);
        return upper.startsWith("LANLINK");
    }

    /** 平台自产来源的站内落地路径；非平台来源返回 null。 */
    public static String internalPathOf(String source) {
        if (!isInternal(source)) return null;
        String s = source.trim();
        return INTERNAL_SOURCES.getOrDefault(s, INTERNAL_PATH);
    }

    /** 必应资讯检索链接：按「标题 + 来源」检索原文，作为无原文链接时的兜底。 */
    public static String searchUrl(String title, String source) {
        String t = title == null ? "" : title.trim();
        String s = source == null ? "" : source.trim();
        String q = (t + " " + s).trim();
        if (q.isEmpty()) q = "大宗商品 价格 行情";
        return "https://www.bing.com/news/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }

    /** 只接受 http/https 绝对链接，其余（空、javascript:、相对路径等）返回 null。 */
    public static String normalizeHttpUrl(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty() || url.length() > 500) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;
        // 去掉空白与反斜杠，避免浏览器解析歧义
        return url.replace("\\", "/").replace(" ", "%20");
    }
}
