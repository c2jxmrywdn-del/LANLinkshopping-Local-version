package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 意图理解服务：本地规则分类为主 + 外部模型可选增强（后台开关）。
 *
 * 约束：外部模型只输出预定义意图标签（经 {@link ExternalIntentClient} 白名单校验），
 * 绝不生成回答文本；外部失败/关闭时回退本地规则。每次分析写意图日志供统计。
 */
@Service
public class IntentUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(IntentUnderstandingService.class);

    private static final List<IntentRule> RULES = List.of(
            new IntentRule("account_access", "账号注册与登录", List.of(
                    "注册", "登录", "登陆", "验证码", "密码", "账号", "找回密码", "修改密码",
                    "短信验证码", "注销", "绑定手机", "换绑", "忘记密码", "安全验证", "重置密码")),
            new IntentRule("merchant_onboarding", "商户入驻", List.of(
                    "商户", "供应商", "入驻", "开店", "招商", "企业入驻", "资质审核", "保证金",
                    "上架商品", "商户合作", "供应商入驻")),
            new IntentRule("product_browse", "商品浏览", List.of(
                    "商品", "产品", "商城", "分类", "行业", "规格", "参数", "型号", "材质",
                    "库存", "现货", "详情", "保质期")),
            new IntentRule("cart_order", "购物车与订单", List.of(
                    "购物车", "结算", "订单", "下单", "采购", "提交订单", "订单号", "改地址",
                    "催单", "取消订单", "合并付款")),
            new IntentRule("wallet_credit", "钱包与账期", List.of(
                    "钱包", "充值", "提现", "账期", "授信", "额度", "还款", "账单", "逾期",
                    "账户余额", "转账")),
            new IntentRule("membership_activity", "会员与活动", List.of(
                    "会员", "积分", "活动", "促销", "优惠", "优惠券", "满减", "折扣", "秒杀",
                    "会员等级", "抵扣")),
            new IntentRule("message_privacy", "消息与隐私", List.of(
                    "消息", "通知", "cookie", "隐私", "站内信", "个人信息", "权限", "免打扰",
                    "隐私政策")),
            new IntentRule("logistics_tracking", "物流与配送", List.of(
                    "物流", "快递", "运单", "发货", "签收", "配送", "运费", "揽收", "快递单号",
                    "到哪了", "运输中", "派件", "查物流", "包裹", "几天到")),
            new IntentRule("refund_aftersale", "退款与售后", List.of(
                    "退款", "退货", "售后", "换货", "维修", "赔付", "退运费", "退差价", "损坏",
                    "补发", "质量问题", "7天无理由", "七天无理由", "不想要了")),
            new IntentRule("payment_invoice", "支付与发票", List.of(
                    "支付", "付款", "发票", "开票", "报销", "对公", "微信支付", "支付宝", "增值税",
                    "专票", "普票", "税号", "付款失败")),
            new IntentRule("complaint_human", "人工客服与投诉", List.of(
                    "人工", "客服", "找人工", "转人工", "人工客服", "真人", "在线人工", "投诉",
                    "差评", "态度恶劣", "投诉商家"))
    );

    private final OutboundModelConfigService configService;
    private final ExternalIntentClient externalIntentClient;
    private final JdbcTemplate jdbc;

    /** 纯本地模式（测试/无外部依赖场景） */
    public IntentUnderstandingService() {
        this(null, null, null);
    }

    @Autowired
    public IntentUnderstandingService(OutboundModelConfigService configService,
                                      ExternalIntentClient externalIntentClient,
                                      JdbcTemplate jdbc) {
        this.configService = configService;
        this.externalIntentClient = externalIntentClient;
        this.jdbc = jdbc;
    }

    public Result analyze(String question, List<String> recentTurns) {
        String text = normalize(question);
        String context = recentTurns == null ? "" : normalize(String.join(" ", recentTurns));
        String combined = text + " " + context;
        IntentRule best = null;
        int bestScore = 0;
        int second = 0;
        for (IntentRule rule : RULES) {
            int score = rule.keywords().stream().mapToInt(k -> combined.contains(k) ? k.length() : 0).sum();
            if (score > bestScore) { second = bestScore; bestScore = score; best = rule; }
            else if (score > second) second = score;
        }
        boolean ambiguous = best == null || bestScore == second || (bestScore > 0 && bestScore - second <= 1);
        double confidence = best == null ? 0.0 : Math.min(0.99, 0.55 + bestScore * 0.08 - (ambiguous ? 0.12 : 0));
        String localIntent = best == null ? "other" : best.code();
        String localCategory = best == null ? "其他" : best.label();
        String reason = best == null ? "未命中已配置意图；建议澄清业务对象或操作。"
                : ambiguous ? "命中多个相近意图；需要补充业务对象或具体操作。"
                : "根据当前问题及最近对话关键词匹配。";

        // 外部模型可选增强：仅开关开启时尝试，失败/非法标签回退本地（外部只出标签不生成回答）
        String intent = localIntent;
        String category = localCategory;
        String source = "local";
        String externalLabel = tryExternalClassify(question);
        if (externalLabel != null) {
            intent = externalLabel;
            category = RULES.stream().filter(r -> r.code().equals(externalLabel))
                    .map(IntentRule::label).findFirst().orElse(category);
            source = "external";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent);
        result.put("category", category);
        result.put("confidence", confidence);
        result.put("ambiguous", ambiguous);
        result.put("source", source);
        result.put("clarification", ambiguous ? "请说明您要处理的是账号、商品、订单、商户、钱包、物流还是售后问题。" : "");
        result.put("reason", reason);
        logIntent(question, intent, source);
        return new Result(result);
    }

    /** 外部意图增强（可选）：开关关闭/未配置/调用失败/违规一律返回 null 回退本地 */
    private String tryExternalClassify(String question) {
        if (configService == null || externalIntentClient == null) return null;
        try {
            if (!configService.toggle("intent_external_enabled")) return null;
            FallbackPolicyGuard.Intent guardIntent = FallbackPolicyGuard.classify(question);
            String denial = FallbackPolicyGuard.authorizeExternalIntent(guardIntent, true);
            if (denial != null) return null;
            String label = externalIntentClient.classify(question, guardIntent);
            // 纵深防御：服务层再校验一次白名单（即使客户端实现有缺陷也不会放行非法标签）
            if (!FallbackPolicyGuard.isSafeIntentLabel(label, ExternalIntentClient.ALLOWED_LABELS)) return null;
            return label;
        } catch (Exception e) {
            log.warn("[intent-enhance] external classify failed, fallback local: {}", e.getMessage());
            return null;
        }
    }

    /** 意图日志（统计用，静默失败不影响主链路） */
    private void logIntent(String question, String intent, String source) {
        if (jdbc == null) return;
        try {
            String hash = CustomerCommunicationService.sha256(
                    question == null ? "" : question.trim());
            jdbc.update("INSERT INTO t_support_intent_log (question_hash, intent, source, created_at) VALUES (?, ?, ?, NOW())",
                    hash, intent, source);
        } catch (Exception e) {
            log.debug("[intent-log] write failed: {}", e.getMessage());
        }
    }

    /** 意图统计（管理端）：按意图/来源分组计数 */
    public List<Map<String, Object>> stats() {
        if (jdbc == null) return List.of();
        try {
            return jdbc.queryForList(
                    "SELECT intent, source, COUNT(*) AS count FROM t_support_intent_log "
                            + "GROUP BY intent, source ORDER BY count DESC LIMIT 50");
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    public record Result(Map<String, Object> data) {}
    private record IntentRule(String code, String label, List<String> keywords) {}
}
