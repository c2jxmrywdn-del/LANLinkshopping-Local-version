package com.lanlink.shopping.module.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.entity.CampaignMerchant;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignMerchantMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignParticipantMapper;
import com.lanlink.shopping.module.campaign.mapper.UserCouponMapper;
import com.lanlink.shopping.module.campaign.entity.CampaignParticipant;
import com.lanlink.shopping.module.campaign.entity.UserCoupon;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统一活动系统服务（营销中台）。
 * 承载原"促销系统(满减/折扣)"与"活动系统(注册/消费有礼)"，并新增：
 *  - 领券活动（coupon）：限量/限领 + 用户券包 + 结算核销
 *  - 限时秒杀（flash_sale）：时间窗内自动应用的折扣促销
 *  - 商户激励（merchant_incentive）：商户报名 + 销售额达标领积分奖励
 *  - 商户报名（joinable）：平台大促活动的商户参与
 *
 * 模块隔离：对外仅暴露 calculateDiscount()（供 PricingFacade 编排）与
 * applyCouponForOrder()/releaseCoupon()（供订单系统集成），不感知订单细节。
 */
@Service
public class CampaignService {

    public static final String TYPE_COUPON = "coupon";
    public static final String TYPE_FULL_REDUCE = "full_reduce";
    public static final String TYPE_DISCOUNT = "discount";
    public static final String TYPE_FLASH_SALE = "flash_sale";
    public static final String TYPE_REGISTER = "register";
    public static final String TYPE_PURCHASE = "purchase";
    public static final String TYPE_MERCHANT_INCENTIVE = "merchant_incentive";

    private static final Set<String> ALL_TYPES = Set.of(TYPE_COUPON, TYPE_FULL_REDUCE, TYPE_DISCOUNT,
            TYPE_FLASH_SALE, TYPE_REGISTER, TYPE_PURCHASE, TYPE_MERCHANT_INCENTIVE);

    /** 计价口径：促销满减/折扣（含秒杀）——与原 PromotionService 完全一致 */
    public BigDecimal calculateDiscount(BigDecimal orderAmount, Long orderIndId) {
        if (orderAmount == null || orderAmount.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal best = BigDecimal.ZERO;
        for (Campaign c : effectiveCampaigns()) {
            if (!c.isPromoType()) continue;
            if (!c.applicable(orderIndId)) continue;
            BigDecimal threshold = c.getThreshold() == null ? BigDecimal.ZERO : c.getThreshold();
            if (orderAmount.compareTo(threshold) < 0) continue;
            BigDecimal benefit = promoBenefit(c, orderAmount);
            if (benefit != null && benefit.compareTo(best) > 0) best = benefit;
        }
        return best;
    }

    /** 单个促销活动的优惠金额（满减=固定额；折扣/秒杀=按率计算，benefitAmount 为封顶） */
    private BigDecimal promoBenefit(Campaign c, BigDecimal orderAmount) {
        if (TYPE_FULL_REDUCE.equals(c.getType())) {
            return c.getBenefitAmount();
        }
        if ((TYPE_DISCOUNT.equals(c.getType()) || TYPE_FLASH_SALE.equals(c.getType()))
                && c.getDiscountRate() != null
                && c.getDiscountRate().compareTo(BigDecimal.ZERO) > 0
                && c.getDiscountRate().compareTo(BigDecimal.ONE) < 0) {
            BigDecimal benefit = orderAmount.multiply(BigDecimal.ONE.subtract(c.getDiscountRate()))
                    .setScale(2, RoundingMode.HALF_UP);
            // 折扣优惠封顶（benefitAmount 有值时生效，防大额订单折扣失控）
            if (c.getBenefitAmount() != null && benefit.compareTo(c.getBenefitAmount()) > 0) {
                return c.getBenefitAmount();
            }
            return benefit;
        }
        return null;
    }

    /** 生效活动（上架 + 时间窗内），缓存供同类多次读取 */
    private List<Campaign> effectiveCampaigns() {
        LocalDateTime now = LocalDateTime.now();
        return campaignMapper.selectList(Wrappers.<Campaign>lambdaQuery()
                .eq(Campaign::getStatus, 1)
                .and(w -> w.isNull(Campaign::getStartTime).or().le(Campaign::getStartTime, now))
                .and(w -> w.isNull(Campaign::getEndTime).or().ge(Campaign::getEndTime, now))
                .orderByDesc(Campaign::getCampaignId));
    }

    // ==================== 用户端：活动广场 ====================

    /** 用户端活动列表（tab: all/coupon/promo/incentive；merchant_incentive 不对用户展示） */
    public List<Map<String, Object>> userOngoing(Long userId, String tab) {
        List<Campaign> acts = effectiveCampaigns().stream()
                .filter(c -> !TYPE_MERCHANT_INCENTIVE.equals(c.getType()))
                .toList();
        Set<String> couponIds = acts.stream().filter(c -> TYPE_COUPON.equals(c.getType()))
                .map(c -> String.valueOf(c.getCampaignId())).collect(Collectors.toSet());
        Set<String> incentiveIds = acts.stream()
                .filter(c -> TYPE_REGISTER.equals(c.getType()) || TYPE_PURCHASE.equals(c.getType()))
                .map(c -> String.valueOf(c.getCampaignId())).collect(Collectors.toSet());
        Map<Long, Long> myCouponCountsRaw = new HashMap<>();
        Set<Long> joinedIdsRaw = new HashSet<>();
        if (userId != null) {
            if (!couponIds.isEmpty()) {
                List<UserCoupon> mine = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                        .eq(UserCoupon::getUserId, userId)
                        .in(UserCoupon::getCampaignId, couponIds.stream().map(Long::valueOf).toList()));
                myCouponCountsRaw = mine.stream().collect(Collectors.groupingBy(UserCoupon::getCampaignId, Collectors.counting()));
            }
            if (!incentiveIds.isEmpty()) {
                List<CampaignParticipant> parts = participantMapper.selectList(Wrappers.<CampaignParticipant>lambdaQuery()
                        .eq(CampaignParticipant::getUserId, userId)
                        .in(CampaignParticipant::getCampaignId, incentiveIds.stream().map(Long::valueOf).toList()));
                joinedIdsRaw = parts.stream().map(CampaignParticipant::getCampaignId).collect(Collectors.toSet());
            }
        }
        final Map<Long, Long> myCouponCounts = myCouponCountsRaw;
        final Set<Long> joinedIds = joinedIdsRaw;
        Map<Long, String> indNames = industryNames(acts);
        return acts.stream()
                .filter(c -> matchTab(c, tab))
                .map(c -> toUserView(c, myCouponCounts.getOrDefault(c.getCampaignId(), 0L),
                        joinedIds.contains(c.getCampaignId()), indNames))
                .toList();
    }

    private boolean matchTab(Campaign c, String tab) {
        if (tab == null || tab.isBlank() || "all".equals(tab)) return true;
        return switch (tab) {
            case "coupon" -> TYPE_COUPON.equals(c.getType());
            case "promo" -> c.isPromoType();
            case "incentive" -> TYPE_REGISTER.equals(c.getType()) || TYPE_PURCHASE.equals(c.getType());
            default -> true;
        };
    }

    /** 单个生效活动详情（不存在/未生效返回 null，前端按"已结束"处理） */
    public Map<String, Object> detail(Long userId, Long campaignId) {
        if (campaignId == null) return null;
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null || !c.effectiveNow(LocalDateTime.now())) return null;
        if (TYPE_MERCHANT_INCENTIVE.equals(c.getType())) return null;
        long claimed = 0;
        boolean joined = false;
        if (userId != null) {
            if (TYPE_COUPON.equals(c.getType())) {
                claimed = userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                        .eq(UserCoupon::getCampaignId, campaignId).eq(UserCoupon::getUserId, userId));
            } else if (TYPE_REGISTER.equals(c.getType())) {
                joined = participantMapper.selectCount(Wrappers.<CampaignParticipant>lambdaQuery()
                        .eq(CampaignParticipant::getCampaignId, campaignId)
                        .eq(CampaignParticipant::getUserId, userId)) > 0;
            }
        }
        Map<Long, String> indNames = industryNames(List.of(c));
        return toUserView(c, claimed, joined, indNames);
    }

    /** 统一用户视图（字段口径与列表/详情一致） */
    private Map<String, Object> toUserView(Campaign c, long myCouponCount, boolean joined, Map<Long, String> indNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campaignId", c.getCampaignId());
        m.put("title", c.getTitle());
        m.put("type", c.getType());
        m.put("description", c.getDescription());
        m.put("accent", c.getAccent());
        m.put("startTime", c.getStartTime());
        m.put("endTime", c.getEndTime());
        m.put("threshold", c.getThreshold());
        m.put("benefitAmount", c.getBenefitAmount());
        m.put("discountRate", c.getDiscountRate());
        m.put("scope", c.getScope());
        m.put("indId", c.getIndId());
        m.put("indName", c.getIndId() == null ? null : indNames.get(c.getIndId()));
        m.put("rewardPoints", c.getRewardPoints());
        // 领券状态
        if (TYPE_COUPON.equals(c.getType())) {
            m.put("perUserLimit", c.getPerUserLimit() == null ? 1 : c.getPerUserLimit());
            m.put("totalQuota", c.getTotalQuota());
            m.put("claimedCount", c.getClaimedCount());
            long remain = c.getTotalQuota() == null || c.getTotalQuota() == 0 ? -1
                    : Math.max(0, c.getTotalQuota() - (c.getClaimedCount() == null ? 0 : c.getClaimedCount()));
            m.put("remainQuota", remain);
            m.put("myCouponCount", myCouponCount);
            boolean limitReached = myCouponCount >= (c.getPerUserLimit() == null ? 1 : c.getPerUserLimit());
            m.put("canClaim", !limitReached && remain != 0);
            m.put("limitReached", limitReached);
        }
        // 激励参与状态
        if (TYPE_REGISTER.equals(c.getType())) {
            m.put("joined", joined);
        }
        if (TYPE_PURCHASE.equals(c.getType())) {
            m.put("joined", true); // 消费有礼：下单支付自动参与，无需手动
        }
        return m;
    }

    /** 领取优惠券（幂等上限：每人限领 N 张；库存原子扣减防超发） */
    @Transactional
    public void claimCoupon(Long userId, Long campaignId) {
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null || !TYPE_COUPON.equals(c.getType())) throw new BusinessException("领券活动不存在");
        if (!c.effectiveNow(LocalDateTime.now())) throw new BusinessException("活动未开始或已结束");
        // 行锁串行化同一活动的领券事务：把「每人限领」的 count→insert 收进临界区，
        // 避免并发请求同时通过限领校验后各自插入导致超领（库存仍由 claimOne 原子扣减）。
        campaignMapper.lockById(campaignId);
        int limit = c.getPerUserLimit() == null ? 1 : c.getPerUserLimit();
        long mine = userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getCampaignId, campaignId).eq(UserCoupon::getUserId, userId));
        if (mine >= limit) throw new BusinessException("已达每人限领 " + limit + " 张上限");
        // 原子扣减发放量（status/时间窗/余量校验全部下沉到 UPDATE 条件，防并发超发）
        if (campaignMapper.claimOne(campaignId) == 0) {
            throw new BusinessException("来晚了，优惠券已抢完");
        }
        UserCoupon uc = new UserCoupon();
        uc.setCampaignId(campaignId);
        uc.setUserId(userId);
        uc.setStatus(0);
        uc.setReceiveTime(LocalDateTime.now());
        userCouponMapper.insert(uc);
    }

    /** 参与激励活动（注册有礼：手动参与领积分；幂等：同一活动不可重复参与） */
    @Transactional
    public void joinIncentive(Long userId, Long campaignId) {
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null || !TYPE_REGISTER.equals(c.getType())) throw new BusinessException("活动不存在");
        if (!c.effectiveNow(LocalDateTime.now())) throw new BusinessException("活动未开始或已结束");
        if (participantMapper.selectCount(Wrappers.<CampaignParticipant>lambdaQuery()
                .eq(CampaignParticipant::getCampaignId, campaignId)
                .eq(CampaignParticipant::getUserId, userId)) > 0) {
            throw new BusinessException("您已参与过该活动");
        }
        int points = c.getRewardPoints() == null ? 0 : c.getRewardPoints();
        CampaignParticipant p = new CampaignParticipant();
        p.setCampaignId(campaignId);
        p.setUserId(userId);
        p.setBonusPoints(points);
        p.setJoinTime(LocalDateTime.now());
        participantMapper.insert(p);
        if (points > 0) {
            membershipService.awardPoints(userId, points, "campaign:" + campaignId, "活动参与奖励：" + c.getTitle());
        }
    }

    /** 我的券包（含有效状态计算：未使用且活动仍在窗内=可用） */
    public List<Map<String, Object>> myCoupons(Long userId) {
        List<UserCoupon> coupons = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getUserId, userId).orderByDesc(UserCoupon::getId));
        if (coupons.isEmpty()) return List.of();
        Map<Long, Campaign> campaigns = campaignsByIds(
                coupons.stream().map(UserCoupon::getCampaignId).collect(Collectors.toSet()));
        Map<Long, String> indNames = industryNames(new ArrayList<>(campaigns.values()));
        return coupons.stream().map(uc -> {
            Campaign c = campaigns.get(uc.getCampaignId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("couponId", uc.getId());
            m.put("campaignId", uc.getCampaignId());
            m.put("title", c == null ? "已删除活动" : c.getTitle());
            m.put("receiveTime", uc.getReceiveTime());
            m.put("usedTime", uc.getUsedTime());
            m.put("orderNo", uc.getOrderNo());
            if (c != null) {
                m.put("threshold", c.getThreshold());
                m.put("benefitAmount", c.getBenefitAmount());
                m.put("discountRate", c.getDiscountRate());
                m.put("scope", c.getScope());
                m.put("indName", c.getIndId() == null ? null : indNames.get(c.getIndId()));
                m.put("endTime", c.getEndTime());
            }
            // 有效状态：1已使用；0且活动仍生效=可用；0但活动已结束/下架=已过期
            String state;
            if (uc.getStatus() != null && uc.getStatus() == 1) {
                state = "used";
            } else if (c != null && c.effectiveNow(LocalDateTime.now())) {
                state = "usable";
            } else {
                state = "expired";
            }
            m.put("state", state);
            return m;
        }).toList();
    }

    /** 我的激励参与记录 */
    public List<Map<String, Object>> myParticipations(Long userId) {
        List<CampaignParticipant> parts = participantMapper.selectList(Wrappers.<CampaignParticipant>lambdaQuery()
                .eq(CampaignParticipant::getUserId, userId).orderByDesc(CampaignParticipant::getJoinTime));
        if (parts.isEmpty()) return List.of();
        Map<Long, Campaign> campaigns = campaignsByIds(
                parts.stream().map(CampaignParticipant::getCampaignId).collect(Collectors.toSet()));
        return parts.stream().map(p -> {
            Campaign c = campaigns.get(p.getCampaignId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaignId", p.getCampaignId());
            m.put("title", c == null ? "已删除活动" : c.getTitle());
            m.put("type", c == null ? null : c.getType());
            m.put("bonusPoints", p.getBonusPoints());
            m.put("joinTime", p.getJoinTime());
            return m;
        }).toList();
    }

    // ==================== 用户端：结算用券 ====================

    /**
     * 结算可用券试算（金额/行业由集成层 PricingFacade 按购物车重算后传入，不信任前端）。
     * @param total  订单原价合计
     * @param indId  订单行业（行业券限定）
     */
    public List<Map<String, Object>> quoteCoupons(Long userId, BigDecimal total, Long indId) {
        List<UserCoupon> unused = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getUserId, userId).eq(UserCoupon::getStatus, 0)
                .orderByDesc(UserCoupon::getId));
        if (unused.isEmpty()) return List.of();
        Map<Long, Campaign> campaigns = campaignsByIds(
                unused.stream().map(UserCoupon::getCampaignId).collect(Collectors.toSet()));
        Map<Long, String> indNames = industryNames(new ArrayList<>(campaigns.values()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserCoupon uc : unused) {
            Campaign c = campaigns.get(uc.getCampaignId());
            if (c == null || !TYPE_COUPON.equals(c.getType())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("couponId", uc.getId());
            m.put("title", c.getTitle());
            m.put("threshold", c.getThreshold());
            m.put("benefitAmount", c.getBenefitAmount());
            m.put("discountRate", c.getDiscountRate());
            m.put("scope", c.getScope());
            m.put("indName", c.getIndId() == null ? null : indNames.get(c.getIndId()));
            m.put("endTime", c.getEndTime());
            boolean applicable = c.effectiveNow(LocalDateTime.now()) && c.applicable(indId)
                    && total.compareTo(c.getThreshold() == null ? BigDecimal.ZERO : c.getThreshold()) >= 0;
            m.put("applicable", applicable);
            m.put("benefit", applicable ? couponBenefit(c, total) : BigDecimal.ZERO);
            result.add(m);
        }
        return result;
    }

    /**
     * 结算核销优惠券（订单集成入口，须在订单事务内调用）。
     * 校验持券人/状态/活动生效/门槛/行业后核销，返回实际优惠金额（封顶为核销前应付）。
     * @param total        订单原价合计（门槛判定基准）
     * @param orderIndId   订单行业（行业券限定）
     * @param payableBeforeCoupon 券前应付（优惠封顶，保证应付 ≥ 0）
     * @param orderNo      核销订单号
     */
    @Transactional
    public BigDecimal applyCouponForOrder(Long userId, Long couponId, BigDecimal total,
                                          Long orderIndId, BigDecimal payableBeforeCoupon, String orderNo) {
        if (couponId == null) return BigDecimal.ZERO;
        UserCoupon uc = userCouponMapper.selectById(couponId);
        if (uc == null || !userId.equals(uc.getUserId())) throw new BusinessException("优惠券不存在");
        if (uc.getStatus() != null && uc.getStatus() == 1) throw new BusinessException("优惠券已被使用");
        if (uc.getStatus() != null && uc.getStatus() == 2) throw new BusinessException("优惠券已过期");
        Campaign c = campaignMapper.selectById(uc.getCampaignId());
        if (c == null || !TYPE_COUPON.equals(c.getType()) || !c.effectiveNow(LocalDateTime.now())) {
            throw new BusinessException("优惠券所在活动已结束");
        }
        BigDecimal threshold = c.getThreshold() == null ? BigDecimal.ZERO : c.getThreshold();
        if (total.compareTo(threshold) < 0) {
            throw new BusinessException("订单金额未满足券门槛（满 ¥" + threshold.stripTrailingZeros().toPlainString() + " 可用）");
        }
        if (!c.applicable(orderIndId)) throw new BusinessException("该优惠券不适用当前订单行业");
        BigDecimal benefit = couponBenefit(c, total);
        if (benefit.compareTo(payableBeforeCoupon) > 0) benefit = payableBeforeCoupon;
        if (benefit.compareTo(BigDecimal.ZERO) < 0) benefit = BigDecimal.ZERO;
        // 原子核销（status 0→1 条件更新）：并发下单时只有一个请求能更新成功，
        // 其余返回 0 行并抛异常，订单事务整体回滚，杜绝同一张券被两笔订单核销。
        if (userCouponMapper.useOne(couponId, userId, orderNo) == 0) {
            throw new BusinessException("优惠券已被使用");
        }
        return benefit;
    }

    /** 优惠券面值：满减券=固定金额；折扣券=按率计算且以 benefitAmount 封顶 */
    private BigDecimal couponBenefit(Campaign c, BigDecimal total) {
        if (c.getDiscountRate() != null && c.getBenefitAmount() != null
                && c.getDiscountRate().compareTo(BigDecimal.ZERO) > 0
                && c.getDiscountRate().compareTo(BigDecimal.ONE) < 0) {
            BigDecimal benefit = total.multiply(BigDecimal.ONE.subtract(c.getDiscountRate()))
                    .setScale(2, RoundingMode.HALF_UP);
            return benefit.compareTo(c.getBenefitAmount()) > 0 ? c.getBenefitAmount() : benefit;
        }
        return c.getBenefitAmount() == null ? BigDecimal.ZERO : c.getBenefitAmount();
    }

    /** 释放订单占用的优惠券（订单取消/全额退款回退，幂等：仅回退本单核销记录） */
    @Transactional
    public void releaseCoupon(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return;
        List<UserCoupon> used = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getOrderNo, orderNo).eq(UserCoupon::getStatus, 1));
        for (UserCoupon uc : used) {
            uc.setStatus(0);
            uc.setUsedTime(null);
            uc.setOrderNo(null);
            userCouponMapper.updateById(uc);
        }
    }

    // ==================== 消费有礼（订单支付事件） ====================

    /** 消费有礼：订单支付事件触发，自动参与所有生效中的 purchase 活动（幂等）。 */
    public void autoJoinPurchase(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Campaign> purchaseActs = campaignMapper.selectList(Wrappers.<Campaign>lambdaQuery()
                .eq(Campaign::getType, TYPE_PURCHASE)
                .eq(Campaign::getStatus, 1)
                .and(w -> w.isNull(Campaign::getEndTime).or().ge(Campaign::getEndTime, now)));
        for (Campaign c : purchaseActs) {
            boolean joined = participantMapper.selectCount(Wrappers.<CampaignParticipant>lambdaQuery()
                    .eq(CampaignParticipant::getCampaignId, c.getCampaignId())
                    .eq(CampaignParticipant::getUserId, userId)) > 0;
            if (!joined) {
                int points = c.getRewardPoints() == null ? 0 : c.getRewardPoints();
                CampaignParticipant p = new CampaignParticipant();
                p.setCampaignId(c.getCampaignId());
                p.setUserId(userId);
                p.setBonusPoints(points);
                p.setJoinTime(now);
                participantMapper.insert(p);
                if (points > 0) {
                    membershipService.awardPoints(userId, points, "campaign:" + c.getCampaignId(),
                            "消费活动奖励：" + c.getTitle());
                }
            }
        }
    }

    // ==================== 商户端：活动报名 + 激励 ====================

    /** 商户可见的活动列表（商户激励 + 开放报名的平台活动，含报名状态与激励进度） */
    public List<Map<String, Object>> merchantCampaigns(Long userId) {
        Merchant m = mustMerchant(userId);
        List<Campaign> acts = effectiveCampaigns().stream()
                .filter(c -> TYPE_MERCHANT_INCENTIVE.equals(c.getType())
                        || (c.getJoinable() != null && c.getJoinable() == 1))
                .toList();
        if (acts.isEmpty()) return List.of();
        List<CampaignMerchant> enrollments = campaignMerchantMapper.selectList(Wrappers.<CampaignMerchant>lambdaQuery()
                .eq(CampaignMerchant::getMerId, m.getMerId()));
        Map<Long, CampaignMerchant> enrolledBy = enrollments.stream()
                .collect(Collectors.toMap(CampaignMerchant::getCampaignId, e -> e, (a, b) -> a));
        Map<Long, String> indNames = industryNames(acts);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Campaign c : acts) {
            CampaignMerchant enrolled = enrolledBy.get(c.getCampaignId());
            Map<String, Object> mv = new LinkedHashMap<>();
            mv.put("campaignId", c.getCampaignId());
            mv.put("title", c.getTitle());
            mv.put("type", c.getType());
            mv.put("description", c.getDescription());
            mv.put("accent", c.getAccent());
            mv.put("startTime", c.getStartTime());
            mv.put("endTime", c.getEndTime());
            mv.put("scope", c.getScope());
            mv.put("indName", c.getIndId() == null ? null : indNames.get(c.getIndId()));
            mv.put("enrolled", enrolled != null);
            if (TYPE_MERCHANT_INCENTIVE.equals(c.getType())) {
                BigDecimal target = c.getMerTargetAmount() == null ? BigDecimal.ZERO : c.getMerTargetAmount();
                BigDecimal sales = merchantSales(c, m.getMerId());
                mv.put("targetAmount", target);
                mv.put("salesAmount", sales);
                int percent = target.compareTo(BigDecimal.ZERO) <= 0 ? 100
                        : sales.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.DOWN)
                        .min(BigDecimal.valueOf(100)).intValue();
                mv.put("progressPercent", percent);
                mv.put("targetReached", target.compareTo(BigDecimal.ZERO) <= 0 || sales.compareTo(target) >= 0);
                mv.put("rewardPoints", c.getMerRewardPoints());
                mv.put("rewarded", enrolled != null && enrolled.getRewarded() != null && enrolled.getRewarded() == 1);
            }
            result.add(mv);
        }
        return result;
    }

    /** 商户报名活动（幂等：同商户同活动仅一条报名记录） */
    @Transactional
    public void merchantJoin(Long userId, Long campaignId) {
        Merchant m = mustMerchant(userId);
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null || !c.effectiveNow(LocalDateTime.now())) throw new BusinessException("活动不存在或已结束");
        boolean joinable = TYPE_MERCHANT_INCENTIVE.equals(c.getType())
                || (c.getJoinable() != null && c.getJoinable() == 1);
        if (!joinable) throw new BusinessException("该活动未开放商户报名");
        if (campaignMerchantMapper.selectCount(Wrappers.<CampaignMerchant>lambdaQuery()
                .eq(CampaignMerchant::getCampaignId, campaignId)
                .eq(CampaignMerchant::getMerId, m.getMerId())) > 0) {
            throw new BusinessException("已报名该活动");
        }
        CampaignMerchant cm = new CampaignMerchant();
        cm.setCampaignId(campaignId);
        cm.setMerId(m.getMerId());
        cm.setStatus(1);
        cm.setRewarded(0);
        cm.setJoinTime(LocalDateTime.now());
        campaignMerchantMapper.insert(cm);
    }

    /** 商户激励领奖：报名 + 窗口内销售额达标 → 发放积分奖励（幂等：rewarded 标记） */
    @Transactional
    public void merchantClaimReward(Long userId, Long campaignId) {
        Merchant m = mustMerchant(userId);
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null || !TYPE_MERCHANT_INCENTIVE.equals(c.getType())) throw new BusinessException("商户激励活动不存在");
        CampaignMerchant cm = campaignMerchantMapper.selectOne(Wrappers.<CampaignMerchant>lambdaQuery()
                .eq(CampaignMerchant::getCampaignId, campaignId).eq(CampaignMerchant::getMerId, m.getMerId()));
        if (cm == null) throw new BusinessException("请先报名该活动");
        if (cm.getRewarded() != null && cm.getRewarded() == 1) throw new BusinessException("奖励已发放");
        BigDecimal target = c.getMerTargetAmount() == null ? BigDecimal.ZERO : c.getMerTargetAmount();
        BigDecimal sales = merchantSales(c, m.getMerId());
        if (target.compareTo(BigDecimal.ZERO) > 0 && sales.compareTo(target) < 0) {
            throw new BusinessException("销售额未达标（当前 ¥" + sales.stripTrailingZeros().toPlainString()
                    + " / 目标 ¥" + target.stripTrailingZeros().toPlainString() + "）");
        }
        int points = c.getMerRewardPoints() == null ? 0 : c.getMerRewardPoints();
        if (points > 0) {
            membershipService.awardPoints(userId, points, "campaign:mer:" + campaignId + ":" + m.getMerId(),
                    "商户激励奖励：" + c.getTitle());
        }
        cm.setRewarded(1);
        campaignMerchantMapper.updateById(cm);
    }

    private BigDecimal merchantSales(Campaign c, Long merId) {
        LocalDateTime start = c.getStartTime() == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : c.getStartTime();
        LocalDateTime end = c.getEndTime() == null ? LocalDateTime.of(9999, 12, 31, 0, 0) : c.getEndTime();
        BigDecimal sales = campaignMapper.merchantSalesInWindow(merId, start, end);
        return sales == null ? BigDecimal.ZERO : sales;
    }

    private Merchant mustMerchant(Long userId) {
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
        if (m == null) throw new BusinessException("仅入驻商户可访问");
        if (m.getReviewStatus() == null || m.getReviewStatus() != 1) throw new BusinessException("商户资质审核未通过，暂不可参与活动");
        return m;
    }

    // ==================== 管理端：活动管理 ====================

    /** 管理端分页（含未上架/已结束，可按类型/状态/关键词过滤） */
    public Page<Campaign> adminPage(long page, long size, String type, Integer status, String keyword) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return campaignMapper.selectPage(new Page<>(page, size), Wrappers.<Campaign>lambdaQuery()
                .eq(type != null && !type.isBlank(), Campaign::getType, type)
                .eq(status != null, Campaign::getStatus, status)
                .like(kw != null, Campaign::getTitle, kw)
                .orderByDesc(Campaign::getCampaignId));
    }

    /** 管理端创建（校验类型化字段；默认下架，由"上架"动作对外发布） */
    public Campaign adminCreate(Campaign c) {
        validateCampaign(c);
        if (c.getStatus() == null) c.setStatus(0);
        if (c.getClaimedCount() == null) c.setClaimedCount(0);
        if (c.getScope() == null || c.getScope().isBlank()) c.setScope("all");
        c.setCreateTime(LocalDateTime.now());
        c.setUpdateTime(LocalDateTime.now());
        campaignMapper.insert(c);
        return c;
    }

    /** 管理端更新（全量字段更新，发放量等计数字段保留原值） */
    public Campaign adminUpdate(Long campaignId, Campaign in) {
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null) throw new BusinessException("活动不存在");
        in.setCampaignId(campaignId);
        in.setClaimedCount(c.getClaimedCount()); // 计数字段不接受外部修改
        validateCampaign(in);
        in.setCreateTime(c.getCreateTime());
        in.setUpdateTime(LocalDateTime.now());
        campaignMapper.updateById(in);
        return in;
    }

    /** 上架/下架 */
    public void adminToggle(Long campaignId, int status) {
        if (status != 0 && status != 1) throw new BusinessException("非法状态值");
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null) throw new BusinessException("活动不存在");
        c.setStatus(status);
        c.setUpdateTime(LocalDateTime.now());
        campaignMapper.updateById(c);
    }

    /** 删除活动（有领券/参与/报名记录时禁止，改为下架） */
    public void adminDelete(Long campaignId) {
        if (userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getCampaignId, campaignId)) > 0) {
            throw new BusinessException("已有用户领券，不能删除（可下架）");
        }
        if (participantMapper.selectCount(Wrappers.<CampaignParticipant>lambdaQuery()
                .eq(CampaignParticipant::getCampaignId, campaignId)) > 0) {
            throw new BusinessException("已有用户参与，不能删除（可下架）");
        }
        if (campaignMerchantMapper.selectCount(Wrappers.<CampaignMerchant>lambdaQuery()
                .eq(CampaignMerchant::getCampaignId, campaignId)) > 0) {
            throw new BusinessException("已有商户报名，不能删除（可下架）");
        }
        campaignMapper.deleteById(campaignId);
    }

    /** 管理端详情 + 运营数据（领取/核销/参与/报名） */
    public Map<String, Object> adminDetail(Long campaignId) {
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null) throw new BusinessException("活动不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campaign", c);
        m.put("claimedCount", c.getClaimedCount() == null ? 0 : c.getClaimedCount());
        m.put("usedCount", userCouponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getCampaignId, campaignId).eq(UserCoupon::getStatus, 1)));
        m.put("participantCount", participantMapper.selectCount(Wrappers.<CampaignParticipant>lambdaQuery()
                .eq(CampaignParticipant::getCampaignId, campaignId)));
        m.put("merchantCount", campaignMerchantMapper.selectCount(Wrappers.<CampaignMerchant>lambdaQuery()
                .eq(CampaignMerchant::getCampaignId, campaignId)));
        return m;
    }

    /** 类型化字段校验（口径与前端表单一致） */
    private void validateCampaign(Campaign c) {
        if (c.getTitle() == null || c.getTitle().isBlank()) throw new BusinessException("活动名称不能为空");
        if (c.getType() == null || !ALL_TYPES.contains(c.getType())) throw new BusinessException("活动类型非法");
        if (c.getStartTime() != null && c.getEndTime() != null && c.getStartTime().isAfter(c.getEndTime())) {
            throw new BusinessException("开始时间不能晚于结束时间");
        }
        switch (c.getType()) {
            case TYPE_FULL_REDUCE -> {
                if (c.getBenefitAmount() == null || c.getBenefitAmount().signum() <= 0) {
                    throw new BusinessException("满减活动必须配置优惠金额");
                }
            }
            case TYPE_DISCOUNT, TYPE_FLASH_SALE -> {
                if (c.getDiscountRate() == null || c.getDiscountRate().signum() <= 0
                        || c.getDiscountRate().compareTo(BigDecimal.ONE) >= 0) {
                    throw new BusinessException("折扣率必须在 0~1 之间（0.95 = 95 折）");
                }
                if (TYPE_FLASH_SALE.equals(c.getType()) && (c.getStartTime() == null || c.getEndTime() == null)) {
                    throw new BusinessException("限时秒杀必须配置起止时间");
                }
            }
            case TYPE_COUPON -> {
                boolean voucher = c.getBenefitAmount() != null && c.getBenefitAmount().signum() > 0;
                boolean rate = c.getDiscountRate() != null && c.getDiscountRate().signum() > 0
                        && c.getDiscountRate().compareTo(BigDecimal.ONE) < 0;
                if (!voucher && !rate) throw new BusinessException("优惠券需配置面值（满减金额或折扣率）");
                if (rate && !voucher) throw new BusinessException("折扣券必须配置优惠封顶金额");
                if (c.getPerUserLimit() == null || c.getPerUserLimit() < 1) c.setPerUserLimit(1);
                if (c.getTotalQuota() == null || c.getTotalQuota() < 0) c.setTotalQuota(0);
            }
            case TYPE_REGISTER, TYPE_PURCHASE -> {
                if (c.getRewardPoints() == null || c.getRewardPoints() <= 0) {
                    throw new BusinessException("激励活动必须配置奖励积分");
                }
            }
            case TYPE_MERCHANT_INCENTIVE -> {
                if (c.getMerTargetAmount() == null || c.getMerTargetAmount().signum() <= 0) {
                    throw new BusinessException("商户激励必须配置目标销售额");
                }
                if (c.getMerRewardPoints() == null || c.getMerRewardPoints() <= 0) {
                    throw new BusinessException("商户激励必须配置奖励积分");
                }
            }
            default -> { }
        }
        if ("ind".equals(c.getScope()) && c.getIndId() == null) {
            throw new BusinessException("行业限定活动必须选择行业");
        }
    }

    // ==================== 公共工具 ====================

    private Map<Long, Campaign> campaignsByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return campaignMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Campaign::getCampaignId, c -> c));
    }

    private Map<Long, String> industryNames(List<Campaign> acts) {
        Set<Long> indIds = acts.stream().map(Campaign::getIndId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (indIds.isEmpty()) return Map.of();
        return industryMapper.selectBatchIds(indIds).stream()
                .collect(Collectors.toMap(Industry::getIndId, Industry::getName));
    }

    private final CampaignMapper campaignMapper;
    private final UserCouponMapper userCouponMapper;
    private final CampaignParticipantMapper participantMapper;
    private final CampaignMerchantMapper campaignMerchantMapper;
    private final MerchantMapper merchantMapper;
    private final IndustryMapper industryMapper;
    private final MembershipService membershipService;

    public CampaignService(CampaignMapper campaignMapper, UserCouponMapper userCouponMapper,
                           CampaignParticipantMapper participantMapper, CampaignMerchantMapper campaignMerchantMapper,
                           MerchantMapper merchantMapper, IndustryMapper industryMapper,
                           MembershipService membershipService) {
        this.campaignMapper = campaignMapper;
        this.userCouponMapper = userCouponMapper;
        this.participantMapper = participantMapper;
        this.campaignMerchantMapper = campaignMerchantMapper;
        this.merchantMapper = merchantMapper;
        this.industryMapper = industryMapper;
        this.membershipService = membershipService;
    }
}
