package com.lanlink.shopping.payment.service;

/**
 * 订阅单支付成功后的业务回调（P1-3）。
 * PaymentService 结算订阅单（pay_status 0→1 原子落账）后调用，由订阅模块实现
 * （SubscriptionService implements 本接口，经 setter 注入避免构造器循环依赖）。
 * 实现方必须幂等：回调重放/渠道重试时重复调用不得重复顺延订阅（apply_status 状态机闸保证）。
 */
public interface SubscriptionPayHandler {

    /**
     * 订阅单已支付：驱动 t_user_subscription 生效/顺延。
     * 续费 = 原到期日起顺延；失效续订（无生效订阅且超出宽限期）= 新起算。
     */
    void onSubscriptionOrderPaid(String orderNo);
}
