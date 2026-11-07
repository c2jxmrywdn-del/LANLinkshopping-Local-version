package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_credit_bill")
public class CreditBill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long userId;
    private String orderNo;
    /** 账单类型：ORDER_PROCUREMENT 采购订单 / LOGISTICS_FREIGHT 运费月结担保 */
    private String billType;
    /** 关联合同或物流结算单号 */
    private String settlementNo;
    /** 冻结状态：FROZEN 在途免息预占 / ACTIVE 见POD起息生效 */
    private String freezeStatus;
    /** 挂接现场公章e-POD回执ID */
    private Long podRecordId;
    /** 现场加盖公章验签通过时间(法定起息锚点) */
    private LocalDateTime podApprovedTime;
    /** 物流信誉利率优惠折扣 (如 0.1500 代表优惠 15%) */
    private BigDecimal logisticsDiscountRate;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal outstandingAmount;
    /** 应还本金部分 */
    private BigDecimal principalAmount;
    /** 应还利息部分 */
    private BigDecimal interestAmount;
    /** 已还利息 */
    private BigDecimal paidInterest;
    /** 逾期罚息部分 */
    private BigDecimal penaltyAmount;
    /** 已还逾期罚息 */
    private BigDecimal paidPenalty;
    /** 计息执行日利率 */
    private BigDecimal dailyRate;
    /** 计息执行年化利率 APR */
    private BigDecimal apr;
    /** 计息起始日 */
    private LocalDate interestStartDate;
    /** 上次计息跑批日期 */
    private LocalDate lastAccrualDate;
    private LocalDate dueDate;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime settledTime;
}
