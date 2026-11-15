package com.lanlink.shopping.dto.refund;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 平台退款复核请求（P0-C 退款出款收口）。
 *
 * <p>用于 {@code POST /admin/refund/review/**}：平台运营对「商户已初审」的退款做第二次复核。
 * 复核通过后由服务端按审批链出款；{@code amount} 用于把本次复核金额锁进审批行，
 * 出款时必须与复核金额一致（防止复核 100 却出款 1000）。
 */
@Data
public class RefundReviewRequest {

    /** 本次申请出款金额（元）；null = 按订单剩余可退金额全额复核（RMA 路径则取售后单申请金额） */
    private BigDecimal amount;

    /** 复核意见（通过时展示给买家的说明 / 驳回时的原因） */
    private String remark;

    /** 驳回原因（仅 reject 接口使用，等价于 remark 的语义别名，二者取非空） */
    private String reason;
}
