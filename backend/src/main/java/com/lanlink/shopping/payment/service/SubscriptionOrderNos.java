package com.lanlink.shopping.payment.service;

/**
 * 订阅单号命名空间（P1-2 单据抽象的分流依据）。
 * 订阅单以「SUB」前缀 + 日期 + 序列生成；商品订单号为纯数字（时间戳+随机位），二者互不相交。
 * /payment/create、/payment/query、/payment/mock-confirm 与三渠道 notify 回调均按该前缀分流到订阅单分支。
 */
public final class SubscriptionOrderNos {

    private SubscriptionOrderNos() {}

    /** 订阅单号前缀（单据类型命名空间） */
    public static final String PREFIX = "SUB";

    /** 是否订阅单号（null 安全） */
    public static boolean isSubscriptionOrderNo(String orderNo) {
        return orderNo != null && orderNo.startsWith(PREFIX);
    }
}
