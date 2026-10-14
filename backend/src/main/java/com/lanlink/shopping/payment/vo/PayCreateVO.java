package com.lanlink.shopping.payment.vo;

import lombok.Data;
import java.util.Map;

/** 发起支付返回：不同渠道 payInfo 结构不同 */
@Data
public class PayCreateVO {
    private String orderNo;
    private String channel;
    /** 是否模拟支付（true 时前端展示"模拟支付成功"按钮即可） */
    private boolean mock;
    /**
     * 渠道支付参数：
     * wechat(NATIVE) -> { "codeUrl": "weixin://..." } 前端生成二维码
     * alipay(电脑网站) -> { "form": "<form ...>" } 前端自动提交跳转
     * mock -> { "token": "..." }
     */
    private Map<String, Object> payInfo;
}
