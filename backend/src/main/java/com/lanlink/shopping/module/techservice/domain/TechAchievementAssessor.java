package com.lanlink.shopping.module.techservice.domain;

import com.lanlink.shopping.module.techservice.entity.TechAchievement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 成果快筛评分器（纯领域逻辑，不依赖 Spring/持久化）。
 *
 * <p>参考 GB/T 44731-2024 与 GB/T 42748-2023 的方法框架做四维快筛：
 * 法律、技术、经济、成熟度。结果为机器初评，不构成评估结论或定价依据。
 */
public final class TechAchievementAssessor {

    public Map<String, Object> assess(TechAchievement a) {
        int seed = Math.abs(Objects.hashCode(a.getTitle()));
        int legal = 60;
        if ("有效".equals(a.getLegalStatus())) legal = 88;
        else if ("审中".equals(a.getLegalStatus())) legal = 72;
        else if ("失效".equals(a.getLegalStatus())) legal = 35;
        if (Integer.valueOf(1).equals(a.getVerified())) legal += 5;
        if ("INVENTION".equals(a.getPatentType())) legal += 4;
        legal = clamp(legal);

        int trl = a.getMaturityLevel() == null ? 5 : a.getMaturityLevel();
        int tech = clamp(30 + trl * 6 + (seed % 13) + (Integer.valueOf(1).equals(a.getVerified()) ? 4 : 0));

        BigDecimal price = a.getExpectedPrice();
        int economic = 52 + (seed % 11);
        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            economic += (int) Math.min(20, Math.log10(price.doubleValue()) * 5);
        }
        if ("OPEN_LICENSE".equals(a.getLicensingMode())) economic += 4;
        economic = clamp(economic);

        int maturity = clamp(trl * 10 + (seed % 5));
        int composite = (int) Math.round(legal * 0.3 + tech * 0.3 + economic * 0.25 + maturity * 0.15);
        String level = composite >= 85 ? "A" : composite >= 70 ? "B" : composite >= 55 ? "C" : "D";
        String levelLabel = "A".equals(level) ? "建议重点运营"
                : "B".equals(level) ? "建议进入匹配池"
                : "C".equals(level) ? "建议先做验证与包装"
                : "建议暂缓投入";

        List<String> reasons = new ArrayList<>();
        reasons.add("法律状态：" + (a.getLegalStatus() == null ? "待核验" : a.getLegalStatus())
                + (Integer.valueOf(1).equals(a.getVerified()) ? "，权属材料已核验" : "，权属材料待核验"));
        reasons.add("技术成熟度：TRL " + trl + (trl >= 7 ? "，接近产业化" : "，建议补做概念验证或中试"));
        reasons.add("许可方式：" + licensingLabel(a.getLicensingMode()) + "；参考价："
                + (price != null && price.compareTo(BigDecimal.ZERO) > 0
                ? "¥" + price.stripTrailingZeros().toPlainString() : "面议"));
        if (Integer.valueOf(1).equals(a.getDormant())) {
            reasons.add("沉睡专利：建议先完成年费、权属与质押/许可状态核查，再制定许可或转让组合方案");
        }
        reasons.add("建议路径：" + (composite >= 70
                ? "优先进入经纪撮合与概念验证资源对接，形成可交易的技术画像"
                : "先补齐技术验证与商业场景描述，再决定是否投入正式评估"));

        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("legal", legal);
        scores.put("technology", tech);
        scores.put("economic", economic);
        scores.put("maturity", maturity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("achievementId", a.getAchievementId());
        out.put("title", a.getTitle());
        out.put("scores", scores);
        out.put("composite", composite);
        out.put("level", level);
        out.put("levelLabel", levelLabel);
        out.put("reasons", reasons);
        out.put("standard", "参考 GB/T 44731-2024《科技成果评估规范》与 GB/T 42748-2023《专利评估指引》的方法框架");
        out.put("disclaimer", "本结果为平台算法快筛，仅供初步判断，不构成评估结论、定价依据或投资建议；正式评估须由具备相应能力的第三方机构出具报告。");
        return out;
    }

    private static String licensingLabel(String mode) {
        if (mode == null) return "面议";
        return switch (mode) {
            case "ASSIGN" -> "转让";
            case "OPEN_LICENSE" -> "开放许可";
            case "LICENSE" -> "许可";
            default -> mode;
        };
    }

    private static int clamp(int value) {
        return Math.max(20, Math.min(98, value));
    }
}
