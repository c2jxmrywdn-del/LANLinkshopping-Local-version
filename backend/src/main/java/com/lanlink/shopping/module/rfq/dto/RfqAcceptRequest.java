package com.lanlink.shopping.module.rfq.dto;

import com.lanlink.shopping.dto.CheckoutDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 买家接受报价并转订单的收货信息。无购物车字段、无幂等键（服务端用 rfq-accept:{quoteId}），
 * expectedTotal 供防篡改比对（必须等于报价总额）。
 */
@Data
public class RfqAcceptRequest {

    @NotBlank(message = "收货人不能为空")
    @Size(max = 40, message = "收货人过长")
    private String receiver;

    @NotBlank(message = "联系电话不能为空")
    @Size(max = 20, message = "联系电话过长")
    private String phone;

    @NotBlank(message = "收货地址不能为空")
    @Size(max = 255, message = "收货地址过长")
    private String address;

    @Size(max = 200, message = "备注过长")
    private String remark;

    private String payType = "corporate";

    /** 客户端期望支付金额（非空时必须与报价总额一致） */
    private BigDecimal expectedTotal;

    /** 兼容误传：报价转单不支持优惠券，非空即由服务层拒绝（400），避免"以为用上了券" */
    private Long couponId;

    /** 兼容误传：报价转单不支持积分抵扣，>0 即拒绝（400） */
    private Integer usePoints;

    public CheckoutDTO toCheckout() {
        CheckoutDTO d = new CheckoutDTO();
        d.setReceiver(receiver);
        d.setPhone(phone);
        d.setAddress(address);
        d.setRemark(remark);
        d.setPayType(payType);
        d.setExpectedTotal(expectedTotal);
        d.setCouponId(couponId);
        d.setUsePoints(usePoints);
        return d;
    }
}