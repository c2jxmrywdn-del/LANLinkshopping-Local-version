package com.lanlink.shopping.support;

import com.lanlink.shopping.module.aikb.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 意图理解单测：本地规则加权决胜（原有行为）+ 本地 7B 文本模型 + 向量 hybrid 兜底。
 *
 * <p>纯本地模式（无参构造器）必须与升级前行为一致；增强链路全部 mock，不依赖 Redis/Ollama/外网。
 * 覆盖 Lead 实测回归："退款/规格"、"发票"、"订单/发货" 必须由规则决胜为 local，不再误入向量/模型。
 */
class IntentUnderstandingServiceTest {
    private final IntentUnderstandingService service = new IntentUnderstandingService();

    private static IntentUnderstandingService withEnhancers(IntentVectorService vector,
                                                            EmbeddingService embedding) {
        return new IntentUnderstandingService(null, null, null, vector, embedding);
    }

    private static IntentVectorService.Classification vectorResult(String intent, String label, double score,
                                                                  double margin) {
        return new IntentVectorService.Classification(intent, label, score, margin,
                List.of(new IntentVectorService.Candidate(intent, label, score)));
    }

    // ==================== 原有本地规则用例（保持不变） ====================

    @Test
    void classifiesColloquialOrderQuestion() {
        var result = service.analyze("我刚才那个单子咋还没动静", List.of("下单后多久发货"));
        assertEquals("cart_order", result.data().get("intent"));
        assertTrue((Double) result.data().get("confidence") > 0.5);
    }

    @Test
    void usesContextForAmbiguousFollowUp() {
        var result = service.analyze("那这个怎么办", List.of("我想申请商户入驻"));
        assertEquals("merchant_onboarding", result.data().get("intent"));
    }

    @Test
    void flagsAmbiguousQuestionForClarification() {
        var result = service.analyze("账号商品都不太清楚", List.of());
        assertEquals(true, result.data().get("ambiguous"));
        assertFalse(String.valueOf(result.data().get("clarification")).isBlank());
    }

    /** 纯本地模式：结果结构新增 candidates/vectorScore 但不破坏旧字段 */
    @Test
    void localModeKeepsLegacyFieldsAndAddsEnhancementFields() {
        Map<String, Object> data = service.analyze("订单怎么查询", List.of()).data();
        assertEquals("local", data.get("source"));
        assertEquals("cart_order", data.get("intent"));
        assertTrue(data.containsKey("candidates"));
        assertTrue(data.containsKey("vectorScore"));
        assertNull(data.get("vectorScore"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) data.get("candidates");
        assertNotNull(candidates);
        assertTrue(candidates.size() <= 3);
        assertEquals("cart_order", candidates.get(0).get("intent"));
    }

    // ==================== 规则加权 + 位置决胜（Lead 实测回归，不得调用模型/向量） ====================

    @Test
    void ruleWeightRewardsEarlierAndLongerKeywords() {
        // 位置越靠前加成越高：0-4 → +2，5-9 → +1，其后 0；长关键词 len/4 额外加成
        assertEquals(4, IntentUnderstandingService.keywordWeight("退款", 0));
        assertEquals(3, IntentUnderstandingService.keywordWeight("退款", 5));
        assertEquals(2, IntentUnderstandingService.keywordWeight("退款", 10));
        assertEquals(5, IntentUnderstandingService.keywordWeight("购物车", 0));
    }

    @Test
    void ruleTieBreakResolvesRefundVsSpec() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        Map<String, Object> data = withEnhancers(vector, embedding)
                .analyze("我要退款，买的钢材规格不对", List.of()).data();

        assertEquals("refund_aftersale", data.get("intent"), "靠前的'退款'应压过靠后的'规格'");
        assertEquals("local", data.get("source"), "规则已决胜时不得走向量/本地模型");
        verify(vector, never()).classify(anyString(), any());
        verify(embedding, never()).chatText(anyString(), anyString());
    }

    @Test
    void ruleDecidesInvoiceIntentWithoutVector() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        Map<String, Object> data = withEnhancers(vector, embedding)
                .analyze("这个月发票什么时候能开出来", List.of()).data();

        assertEquals("payment_invoice", data.get("intent"));
        assertEquals("local", data.get("source"));
        verify(vector, never()).classify(anyString(), any());
        verify(embedding, never()).chatText(anyString(), anyString());
    }

    @Test
    void ruleDecidesOrderShippingIntent() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        Map<String, Object> data = withEnhancers(vector, embedding)
                .analyze("帮我看看订单什么时候发货", List.of()).data();

        // 边界口径：问「什么时候发货」时货还没发，属订单域（承运后才归物流），不再允许二选一
        assertEquals("cart_order", data.get("intent"), "发货前追问应由规则判为订单域");
        assertNotEquals("complaint_human", data.get("intent"));
        assertEquals("local", data.get("source"));
        verify(vector, never()).classify(anyString(), any());
        verify(embedding, never()).chatText(anyString(), anyString());
    }

    // ==================== 本地 7B 文本模型优先于向量（规则未命中/歧义时） ====================

    @Test
    void usesLlmBeforeVectorWhenRulesCannotDecide() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.chatText(anyString(), anyString())).thenReturn("cart_order");

        Map<String, Object> data = withEnhancers(vector, embedding)
                .analyze("这件东西有点说不清呢", List.of()).data();

        assertEquals("cart_order", data.get("intent"));
        assertEquals("llm", data.get("source"));
        verify(vector, never()).classify(anyString(), any());
    }

    @Test
    void localTextModelIllegalLabelFallsBackToLocal() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.chatText(anyString(), anyString())).thenReturn("我觉得可能是投诉或者退款吧");

        Map<String, Object> data = withEnhancers(vector, embedding).analyze("那个东西不太行", List.of()).data();

        assertEquals("local", data.get("source"), "非白名单输出必须丢弃并回退规则");
        assertEquals("other", data.get("intent"));
    }

    @Test
    void llmFailureFallsBackToVectorHybrid() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.chatText(anyString(), anyString())).thenThrow(new RuntimeException("gateway down"));
        when(vector.minScore()).thenReturn(0.55);
        when(vector.classify(anyString(), any())).thenReturn(
                vectorResult("logistics_tracking", "物流与配送", 0.72, 0.12));

        Map<String, Object> data = withEnhancers(vector, embedding)
                .analyze("这件东西有点说不清呢", List.of()).data();

        assertEquals("logistics_tracking", data.get("intent"));
        assertEquals("vector", data.get("source"));
        assertEquals(0.72, (Double) data.get("confidence"), 1e-6);
        assertEquals(0.72, (Double) data.get("vectorScore"), 1e-6);
    }

    @Test
    void vectorFailureFallsBackToLocalSilently() {
        IntentVectorService vector = mock(IntentVectorService.class);
        when(vector.minScore()).thenReturn(0.55);
        when(vector.classify(anyString(), any())).thenThrow(new RuntimeException("redis down"));

        Map<String, Object> data = withEnhancers(vector, null)
                .analyze("这件东西有点说不清呢", List.of()).data();

        assertEquals("local", data.get("source"));
        assertEquals("other", data.get("intent"));
    }

    @Test
    void vectorLowScoreFallsBackToLocal() {
        IntentVectorService vector = mock(IntentVectorService.class);
        when(vector.minScore()).thenReturn(0.55);
        when(vector.classify(anyString(), any())).thenReturn(vectorResult("cart_order", "购物车与订单", 0.40, 0.05));

        Map<String, Object> data = withEnhancers(vector, null)
                .analyze("这件东西有点说不清呢", List.of()).data();

        assertEquals("local", data.get("source"));
        assertEquals("other", data.get("intent"));
    }

    @Test
    void localTextModelCanBeDisabled() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.chatText(anyString(), anyString())).thenReturn("refund_aftersale");
        IntentUnderstandingService enhanced = withEnhancers(vector, embedding);
        enhanced.setLlmEnabled(false);

        Map<String, Object> data = enhanced.analyze("那个东西不太行", List.of()).data();

        assertEquals("local", data.get("source"));
        verify(embedding, never()).chatText(anyString(), anyString());
    }

    // ==================== candidates 三路合并 / 规则优先不干扰 ====================

    @Test
    void mergesRuleAndLlmCandidates() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.chatText(anyString(), anyString())).thenReturn("refund_aftersale");

        Map<String, Object> data = withEnhancers(vector, embedding)
                .analyze("订单和退款到底哪个", List.of()).data();

        assertEquals("llm", data.get("source"));
        assertEquals("refund_aftersale", data.get("intent"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) data.get("candidates");
        assertEquals(2, candidates.size(), "应合并采纳意图与规则候选");
        assertEquals("refund_aftersale", candidates.get(0).get("intent"));
        assertEquals("cart_order", candidates.get(1).get("intent"));
    }

    @Test
    void mergesRuleAndVectorCandidates() {
        IntentVectorService vector = mock(IntentVectorService.class);
        when(vector.minScore()).thenReturn(0.55);
        when(vector.classify(anyString(), any())).thenReturn(new IntentVectorService.Classification(
                "cart_order", "购物车与订单", 0.68, 0.10,
                List.of(new IntentVectorService.Candidate("cart_order", "购物车与订单", 0.68),
                        new IntentVectorService.Candidate("logistics_tracking", "物流与配送", 0.58))));

        // 「物流」「订单」各命中一个等权关键词 → 规则有候选但不决胜，才能真正测到三路候选合并
        Map<String, Object> data = withEnhancers(vector, null).analyze("物流和订单一起看下", List.of()).data();

        assertEquals("vector", data.get("source"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) data.get("candidates");
        assertFalse(candidates.isEmpty());
        assertTrue(candidates.size() <= 3);
        assertEquals("cart_order", candidates.get(0).get("intent"));
    }

    @Test
    void strongRuleMatchSkipsVectorAndTextModel() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        IntentUnderstandingService enhanced = withEnhancers(vector, embedding);

        Map<String, Object> data = enhanced.analyze("购物车怎么结算，订单怎么下单，订单号在哪里查", List.of()).data();

        assertEquals("cart_order", data.get("intent"));
        assertEquals("local", data.get("source"));
        verify(vector, never()).classify(anyString(), any());
        verify(embedding, never()).chatText(anyString(), anyString());
    }

    // ==================== 英文意图关键词（客服双语支持） ====================

    @Test
    void englishIntentQuestionsHitRulesDirectly() {
        String[][] cases = {
                {"I cannot login and need to reset password", "account_access"},
                {"How to apply for merchant onboarding as a supplier", "merchant_onboarding"},
                {"How to apply for merchant onboarding", "merchant_onboarding"},
                {"What is the product specification and price", "product_browse"},
                {"How to checkout my shopping cart order", "cart_order"},
                {"My wallet balance is overdue for repayment", "wallet_credit"},
                {"How to redeem points and coupon for member discount", "membership_activity"},
                {"Turn off message notification and cookie privacy", "message_privacy"},
                {"Where is my parcel? shipping tracking not updated", "logistics_tracking"},
                {"I want a refund for damaged goods", "refund_aftersale"},
                {"How to get invoice and payment receipt", "payment_invoice"},
                {"I want to complain to a human agent", "complaint_human"},
        };
        for (String[] testCase : cases) {
            Map<String, Object> data = service.analyze(testCase[0], List.of()).data();
            assertEquals(testCase[1], data.get("intent"), testCase[0] + " 应命中 " + testCase[1]);
            assertEquals("local", data.get("source"), testCase[0] + " 应由英文规则直接命中 local");
        }
    }

    @Test
    void englishRefundQuestionDecidedByRulesWithoutModel() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);

        Map<String, Object> data = withEnhancers(vector, embedding)
                .analyze("I want a refund for damaged goods", List.of()).data();

        assertEquals("refund_aftersale", data.get("intent"));
        assertEquals("local", data.get("source"), "英文规则命中时不得调用本地模型/向量");
        verify(vector, never()).classify(anyString(), any());
        verify(embedding, never()).chatText(anyString(), anyString());
    }

    // ==================== 价格类问法（询价场景，Lead 实测补充） ====================

    @Test
    void priceQuestionsMapToProductBrowse() {
        assertEquals("product_browse", service.analyze("螺纹钢现在什么价格？", List.of()).data().get("intent"));
        assertEquals("product_browse", service.analyze("螺纹钢今天报价多少", List.of()).data().get("intent"));
        assertEquals("product_browse", service.analyze("这个型号多少钱", List.of()).data().get("intent"));
        assertEquals("product_browse", service.analyze("how much is the rebar", List.of()).data().get("intent"));
        assertEquals("product_browse", service.analyze("what is the pricing and quote", List.of()).data().get("intent"));
        assertEquals("local", service.analyze("螺纹钢现在什么价格？", List.of()).data().get("source"));
    }

    // ==================== 本地模型输出解析（白名单） ====================

    @Test
    void parsesWhitelistedLabelFromModelOutput() {
        assertEquals("cart_order", IntentUnderstandingService.parseLlmLabel("cart_order"));
        assertEquals("logistics_tracking", IntentUnderstandingService.parseLlmLabel("标签：Logistics_Tracking\n"));
        assertNull(IntentUnderstandingService.parseLlmLabel("other"));
        assertNull(IntentUnderstandingService.parseLlmLabel("cart_orders"));
        assertNull(IntentUnderstandingService.parseLlmLabel("随便聊聊"));
        assertNull(IntentUnderstandingService.parseLlmLabel(null));
    }

    @Test
    void parsesStrictJsonIntentOutput() {
        assertEquals("cart_order", IntentUnderstandingService.parseLlmLabel("{\"intent\": \"cart_order\"}"));
        assertEquals("refund_aftersale",
                IntentUnderstandingService.parseLlmLabel("```json\n{\"intent\": \"refund_aftersale\"}\n```"));
        assertEquals("payment_invoice",
                IntentUnderstandingService.parseLlmLabel("好的，答案是 {\"intent\": \"payment_invoice\"}"));
        // JSON 信封是模型明确的结构化作答：越界 code 按训练口径归 other，而不是丢弃
        assertEquals("other", IntentUnderstandingService.parseLlmLabel("{\"intent\": \"other\"}"));
        assertEquals("other", IntentUnderstandingService.parseLlmLabel("{\"intent\": \"member_center\"}"));
        // 无 JSON 的乱码仍返回 null，交给向量/规则兜底，不能强行判成 other
        assertNull(IntentUnderstandingService.parseLlmLabel("我觉得可能是投诉或者退款吧"));
    }

    @Test
    void llmPromptCarriesTheTrainingJsonContract() {        String prompt = IntentUnderstandingService.LLM_INTENT_SYSTEM_PROMPT;
        assertTrue(prompt.contains("只输出 JSON：{\"intent\": \"<code>\"}"), "必须是严格 JSON 契约");
        assertTrue(prompt.contains("{\"intent\": \"other\"}"), "必须给出 other 的出口");
        // 与 training/config.py INTENTS 同一份 code/label；任一侧变动都要同步另一侧，故此断言是漂移哨兵
        for (String line : List.of(
                "- account_access: 账号注册与登录", "- merchant_onboarding: 商户入驻",
                "- product_browse: 商品浏览", "- cart_order: 购物车与订单",
                "- wallet_credit: 钱包与账期", "- membership_activity: 会员与活动",
                "- message_privacy: 消息与隐私", "- logistics_tracking: 物流与配送",
                "- refund_aftersale: 退款与售后", "- payment_invoice: 支付与发票",
                "- complaint_human: 人工客服与投诉")) {
            assertTrue(prompt.contains(line), "提示词缺少标签行：" + line);
        }
    }

    @Test
    void llmOtherLabelKeepsIntentAndCategoryConsistent() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.chatText(anyString(), anyString())).thenReturn("{\"intent\": \"other\"}");

        Map<String, Object> data = withEnhancers(vector, embedding)
                .analyze("今天天气怎么样", List.of()).data();

        assertEquals("other", data.get("intent"));
        assertEquals("其他", data.get("category"), "判为 other 时不能沿用规则候选的中文类目");
        assertEquals("llm", data.get("source"));
    }

    @Test
    void userMessageIsTheBareQuestion() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.chatText(anyString(), anyString())).thenReturn("{\"intent\": \"cart_order\"}");

        withEnhancers(vector, embedding).analyze("这个东西不太靠谱呢", List.of());

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(embedding).chatText(anyString(), user.capture());
        assertEquals("这个东西不太靠谱呢", user.getValue(),
                "训练样本的 user 内容就是问题原文，生产侧不得再加「用户问题：」前缀");
    }

    // ==================== 订单域 / 物流域边界口径 ====================
    // 业界通行划分（AWS Lex CheckOrderStatus、Rasa ask_order_status、电商客服提示词样例）都不是按
    // 「订单/物流」字面词切，而是按履约状态切：尚未发货的订单沉默属订单域，已进入承运（快递、运单、
    // 轨迹、包裹、签收）属物流域。本项目的买家端只有 /orders，物流后台仅商家/运营可见，因此
    // 「下了单没动静」类问题只有订单答案能落地——边界必须由规则决胜，不能交给模型掷硬币。

    @Test
    void preShipmentOrderSilenceBelongsToCartOrder() {
        String[] questions = {
                "我刚才那个单子咋还没动静",
                "帮我看看订单什么时候发货",
                "下单三天了还没发货怎么办",
                "我的订单发货了吗",
                "这个订单帮我催发货",
                "订单到哪了",
                "我的订单到哪了",
                "Where is my order?",
                "What is the status of my order?",
        };
        for (String q : questions) {
            Map<String, Object> data = service.analyze(q, List.of()).data();
            assertEquals("cart_order", data.get("intent"), "发货前的订单沉默属订单域：" + q);
            assertEquals("local", data.get("source"), "边界问法应由规则决胜，不该交给模型/向量：" + q);
        }
    }

    @Test
    void postShipmentCarrierQuestionsBelongToLogistics() {
        String[] questions = {
                "快递怎么还没送到",
                "物流信息一直不更新",
                "订单显示已发货但一直没收到包裹",
                "查一下运单号和轨迹",
                "包裹显示签收但不是我自己签的",
                "How to track my shipment?",
                "Where is my parcel? shipping tracking not updated",
        };
        for (String q : questions) {
            Map<String, Object> data = service.analyze(q, List.of()).data();
            assertEquals("logistics_tracking", data.get("intent"), "已进入承运属物流域：" + q);
            assertEquals("local", data.get("source"), "边界问法应由规则决胜，不该交给模型/向量：" + q);
        }
    }

    @Test
    void boundaryIsDecidedWithoutCallingModelOrVector() {
        IntentVectorService vector = mock(IntentVectorService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        IntentUnderstandingService enhanced = withEnhancers(vector, embedding);

        assertEquals("cart_order", enhanced.analyze("我刚才那个单子咋还没动静", List.of()).data().get("intent"));
        assertEquals("logistics_tracking", enhanced.analyze("快递走到哪了", List.of()).data().get("intent"));

        verify(embedding, never()).chatText(anyString(), anyString());
        verify(vector, never()).classify(anyString(), any());
    }

    @Test
    void deliveryAddressPhraseIsNotStolenByGenericDeliveryKeyword() {
        // cart_order 的「delivery address / 改地址」比 logistics 的裸「delivery」更具体，权重应更高
        assertEquals("cart_order", service.analyze("怎么改 delivery address", List.of()).data().get("intent"));
    }
}
