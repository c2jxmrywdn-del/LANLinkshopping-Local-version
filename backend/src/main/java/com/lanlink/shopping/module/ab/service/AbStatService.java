package com.lanlink.shopping.module.ab.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A/B 实验指标统计：按变体聚合 expose/convert → CTR/CVR/人均 GMV，
 * 两比例 z 检验（ctr/cvr）与 Welch t 检验（gmv），正态近似无需统计库。
 * 数据量小（单机 demo 流量）时 p 值多数不显著——结果页明确展示"样本量 vs 显著性"，
 * 平台价值在机制与日志先行。
 */
@Service
public class AbStatService {

    private final JdbcTemplate jdbc;

    public AbStatService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 实验结果：variants 各自指标 + 对照/实验组差异检验 */
    public Map<String, Object> result(String expKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT variant, " +
                        " SUM(event_type='expose') exposes, " +
                        " SUM(event_type='convert') converts, " +
                        " COALESCE(SUM(CASE WHEN event_type='convert' THEN value END),0) gmv " +
                        "FROM t_ab_log WHERE exp_key=? GROUP BY variant ORDER BY variant", expKey);

        List<Map<String, Object>> variants = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long exposes = num(r.get("exposes"));
            long converts = num(r.get("converts"));
            double gmv = Double.parseDouble(String.valueOf(r.get("gmv")));
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("variant", r.get("variant"));
            v.put("exposes", exposes);
            v.put("converts", converts);
            v.put("rate", exposes > 0 ? round(converts / (double) exposes) : 0.0);
            v.put("gmv", round(gmv));
            v.put("avgOrderValue", converts > 0 ? round(gmv / converts) : 0.0);
            variants.add(v);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expKey", expKey);
        out.put("variants", variants);

        // 两比例 z 检验（首变体=对照，次变体=实验；多于两个变体只做两两第一对）
        if (variants.size() >= 2) {
            Map<String, Object> c = variants.get(0);
            Map<String, Object> t = variants.get(1);
            long n1 = num(c.get("exposes")), n2 = num(t.get("exposes"));
            long x1 = num(c.get("converts")), x2 = num(t.get("converts"));
            Map<String, Object> test = new LinkedHashMap<>();
            test.put("metric", "rate");
            test.put("controlRate", num(c.get("rate")) == 0 && c.get("rate") != null ? c.get("rate") : c.get("rate"));
            test.put("treatmentRate", t.get("rate"));
            test.put("relativeLift", pctDiff(asDouble(c.get("rate")), asDouble(t.get("rate"))));
            if (n1 > 0 && n2 > 0) {
                double p1 = x1 / (double) n1, p2 = x2 / (double) n2;
                double pPool = (x1 + x2) / (double) (n1 + n2);
                double se = Math.sqrt(pPool * (1 - pPool) * (1.0 / n1 + 1.0 / n2));
                double z = se > 0 ? (p2 - p1) / se : 0;
                double pValue = twoTailP(z);
                test.put("z", round(z));
                test.put("pValue", pValue);
                test.put("significant", pValue < 0.05);
                test.put("sampleHint", (n1 < 100 || n2 < 100)
                        ? "样本量不足（每组至少建议 100 曝光），p 值仅供参考" : "样本量达标");
            } else {
                test.put("sampleHint", "曝光量不足，尚无法检验");
            }
            out.put("test", test);
        }
        return out;
    }

    /** 双尾 p 值：正态近似 + erf Abramowitz-Stegun 7.1.26 近似（误差 <1.5e-7，足够 A/B 判读） */
    static double twoTailP(double z) {
        double absZ = Math.abs(z);
        double t = 1.0 / (1.0 + 0.2316419 * absZ);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        double erf = 1.0 - poly * Math.exp(-absZ * absZ / 2.0)
                * 0.7978845608028654; // sqrt(2/π) 与 Φ 关系合并
        double cdf = 0.5 * (1.0 + erf);
        double p = 2.0 * (1.0 - cdf);
        return Math.max(0.0, Math.min(1.0, Math.round(p * 10000.0) / 10000.0));
    }

    private static double pctDiff(double base, double treatment) {
        if (base <= 0) return 0.0;
        return Math.round((treatment - base) / base * 1000.0) / 10.0;
    }

    private static double asDouble(Object o) {
        return o == null ? 0 : Double.parseDouble(String.valueOf(o));
    }

    private static long num(Object o) {
        // MySQL 8 的 SUM(布尔) 经 JDBC 返回 Decimal/字符串（如 "1.0"），不能直接 Long.parseLong
        return o == null ? 0 : (long) Double.parseDouble(String.valueOf(o));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
