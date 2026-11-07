package com.lanlink.shopping.module.campaign.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 活动（营销中台·统一活动系统）
 * type: coupon领券 / full_reduce满减 / discount折扣 / flash_sale限时秒杀
 *       register注册有礼 / purchase消费有礼 / merchant_incentive商户激励
 * status: 0已下架 / 1已上架（生效 = 上架 且 在时间窗内）
 */
@Data
@Accessors(chain = true)
@TableName("t_campaign")
public class Campaign {
    @TableId(type = IdType.AUTO)
    private Long campaignId;
    private String title;
    private String type;
    private String description;
    /** 卡片主题色：blue/red/gold/green/purple（前端渐变点缀） */
    private String accent;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;

    // ===== 促销/领券规则（full_reduce / discount / flash_sale / coupon）=====
    /** 门槛金额（满 X 可用） */
    private BigDecimal threshold;
    /** 优惠金额（满减/满减券）；折扣类为优惠封顶金额 */
    private BigDecimal benefitAmount;
    /** 折扣率（0.95 = 95 折） */
    private BigDecimal discountRate;
    /** 适用范围 all 全场 / ind 行业 */
    private String scope;
    /** 限定行业（scope=ind 时） */
    private Long indId;

    // ===== 领券配置（coupon）=====
    /** 每人限领张数 */
    private Integer perUserLimit;
    /** 发放总量（0=不限量） */
    private Integer totalQuota;
    /** 已领取张数 */
    private Integer claimedCount;

    // ===== 激励配置（register / purchase / merchant_incentive）=====
    /** 用户参与奖励积分 */
    private Integer rewardPoints;
    /** 商户激励目标销售额 */
    private BigDecimal merTargetAmount;
    /** 商户激励奖励积分 */
    private Integer merRewardPoints;
    /** 商户可报名（1开放报名） */
    private Integer joinable;

    // ===== 商户自建促销（mer_id 非空 = 商户活动）=====
    /** 创建商户（NULL=平台活动） */
    private Long merId;
    /** 指定商品ID逗号分隔（scope=prod 时） */
    private String prodIds;
    /** 审核状态 0待审 1通过 2驳回（平台活动恒 1） */
    private Integer reviewStatus;
    /** 审核意见（驳回时填写） */
    private String reviewNote;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 促销类（结算自动应用） */
    public boolean isPromoType() {
        return "full_reduce".equals(type) || "discount".equals(type) || "flash_sale".equals(type);
    }

    /** 是否对指定行业订单适用 */
    public boolean applicable(Long orderIndId) {
        return !"ind".equals(scope) || (indId != null && indId.equals(orderIndId));
    }

    /** 是否对指定商品适用：all→true；ind→行业相等；prod→商品ID在 prod_ids 内 */
    public boolean applicableForProduct(Long prodId, Long indId) {
        if ("prod".equals(scope)) {
            return prodId != null && prodIdSet().contains(prodId);
        }
        return applicable(indId);
    }

    /** 解析 prod_ids 逗号分隔商品ID集合（空/非法段忽略） */
    public Set<Long> prodIdSet() {
        if (prodIds == null || prodIds.isBlank()) return Set.of();
        Set<Long> out = new HashSet<>();
        for (String s : prodIds.split(",")) {
            try {
                out.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignore) {
                // 容忍脏数据段，不影响其他商品匹配
            }
        }
        return out;
    }

    /** 商户促销是否审核通过（平台活动 mer_id 为空视为恒通过，兼容存量行） */
    public boolean reviewApproved() {
        return merId == null || (reviewStatus != null && reviewStatus == 1);
    }

    /** 上架且在时间窗内 */
    public boolean effectiveNow(LocalDateTime now) {
        return status != null && status == 1
                && (startTime == null || !startTime.isAfter(now))
                && (endTime == null || !endTime.isBefore(now));
    }
}
