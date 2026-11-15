package com.lanlink.shopping.module.marketingimage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 营销图生成配置。
 *
 * <p>集中到属性类而不是散落在 Service 的 @Value 字段里，是为了单测能直接 new + set，
 * 不必启动 Spring 容器，也不必反射改私有字段。
 */
@Component
public class MarketingImageProperties {

    /** 总开关：默认关闭，避免未充值的部署被误触发计费外呼 */
    @Value("${MARKETING_IMAGE_ENABLED:false}")
    private boolean enabled;

    /** 万相代理 Key（环境变量已注入；缺失时提交后任务会明确失败而不是静默空转） */
    @Value("${AI_WANX_API_KEY:}")
    private String apiKey;

    @Value("${AI_WANX_BASE_URL:https://maas.qianwenaiapi.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${AI_WANX_IMAGE_MODEL:qwen-image-2.1-turbo}")
    private String imageModel;

    @Value("${AI_WANX_IMAGE_MODEL_PRO:qwen-image-2.1-pro}")
    private String imageModelPro;

    /** 第三个可选模型：qwen-mt-image-2.0（代理开通前调用会 404，由前端明确提示） */
    @Value("${AI_WANX_IMAGE_MODEL_MT:qwen-mt-image-2.0}")
    private String imageModelMt;

    /** 月度预算（元），与 cost_micros 口径换算：1 元 = 1_000_000 微元 */
    @Value("${AI_WANX_BUDGET_CNY:200}")
    private long budgetCny;

    /** 单张估算成本（微元），0.22 元/张 */
    @Value("${MARKETING_IMAGE_UNIT_COST_MICROS:220000}")
    private long unitCostMicros;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String imageModel) { this.imageModel = imageModel; }

    public String getImageModelPro() { return imageModelPro; }
    public void setImageModelPro(String imageModelPro) { this.imageModelPro = imageModelPro; }

    public String getImageModelMt() { return imageModelMt; }
    public void setImageModelMt(String imageModelMt) { this.imageModelMt = imageModelMt; }

    public long getBudgetCny() { return budgetCny; }
    public void setBudgetCny(long budgetCny) { this.budgetCny = budgetCny; }

    public long getUnitCostMicros() { return unitCostMicros; }
    public void setUnitCostMicros(long unitCostMicros) { this.unitCostMicros = unitCostMicros; }

    /** 月度预算换算成微元，供预算闸与前端展示共用同一口径 */
    public long monthlyBudgetMicros() {
        return budgetCny * 1_000_000L;
    }
}
