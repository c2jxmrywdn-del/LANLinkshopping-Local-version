package com.lanlink.shopping.dto;

import com.lanlink.shopping.common.ValidationRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

/**
 * 购物车结算下单（前后端双重校验：字段合法性见注解，金额/价格由后端重算不信任前端）
 * 长度限制取自 {@link ValidationRules} 契约常量，与前端 validationRules.js 同口径。
 */
@Data
public class CheckoutDTO {
    /** 结算的购物车行ID列表 */
    private List<Long> cartIds;
    @NotBlank(message = "收货人不能为空")
    @Size(max = ValidationRules.RECEIVER_MAX, message = "收货人过长")
    private String receiver;
    @NotBlank(message = "联系电话不能为空")
    @Size(max = ValidationRules.PHONE_MAX, message = "联系电话过长")
    private String phone;
    @NotBlank(message = "收货地址不能为空")
    @Size(max = ValidationRules.ADDRESS_MAX, message = "收货地址过长")
    private String address;
    @Size(max = ValidationRules.REMARK_MAX, message = "备注过长")
    private String remark;
    /** 支付方式 balance 余额 / corporate 对公转账 / term 账期 */
    private String payType = "corporate";
    /** 本单使用的积分数（积分抵现，0 或 null 表示不使用；上限受单笔抵扣比例约束） */
    private Integer usePoints;
    /** 本单使用的用户券ID（t_user_coupon.id；null 表示不使用，核销校验与金额由后端重算） */
    private Long couponId;
    /** 下单归因直播间ID（从直播间讲解商品进入下单链路时由前端携带，后端校验直播间存在） */
    private Long liveRoomId;
    /**
     * 客户端期望支付金额（防篡改比对：非空时与后端重算总额比对，不一致则告警拒绝）
     */
    private java.math.BigDecimal expectedTotal;
    /**
     * 幂等键（需求 2：防重复提交）。前端在打开结算页时生成 UUID，
     * 同一结算动作重试必须携带同一 key，服务端据此拦截重复下单。
     */
    private String idempotentKey;
}
