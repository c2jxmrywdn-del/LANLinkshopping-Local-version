package com.lanlink.shopping.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 发起支付请求 */
@Data
public class PayCreateRequest {
    @NotBlank(message = "订单号不能为空")
    private String orderNo;
    /** 支付渠道：wechat | alipay | mock（缺省按服务端 mock 开关决定） */
    private String channel;
}
