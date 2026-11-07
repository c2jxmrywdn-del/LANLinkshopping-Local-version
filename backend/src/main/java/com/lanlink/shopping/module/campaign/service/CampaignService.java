package com.lanlink.shopping.module.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.MerchantPromoDTO;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.entity.CampaignMerchant;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignMerchantMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignParticipantMapper;
import com.lanlink.shopping.module.campaign.mapper.UserCouponMapper;
import com.lanlink.shopping.module.campaign.entity.CampaignParticipant;
import com.lanlink.shopping.module.campaign.entity.UserCoupon;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.springframework.beans.factory.annotation.Autowired;
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
 *  - 领券活动（coupon）：限量/限领 + 用户券包 + 结算核销（含 scope=prod 店铺券）
 *  - 限时秒杀（flash_sale）：时间窗内自动应用的折扣促销
 *  - 商户激励（merchant_incentive）：商户报名 + 销售额达标领积分奖励
 *  - 商户报名（joinable）：平台大促活动的商户参与
 *  - 商户自建促销（scope=prod + mer_id）：商户指定本商户商品创建满减/折扣/秒杀，
 *    提交后待平台审核（review_status=0→1/2），通过后按订单行参与结算计价
 *
 * 模块隔离：对外仅暴露 calculateDiscount()/calculateDiscountDetailed()（供 PricingFacade 编排）与
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

    /** 适用范围：全场 / 行业 / 指定商品（商户自建促销与店铺券） */
    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_IND = "ind";
    public static final String SCOPE_PROD = "prod";

    /** 商户自建促销允许的类型（满减/折扣/秒杀） */
    private static final Set<String> MERCHANT_PROMO_TYPES =
            Set.of(TYPE_FULL_REDUCE, TYPE_DISCOUNT, TYPE_FLASH_SALE);

    private static final Set<String> ALL_TYPES = Set.of(TYPE_COUPON, TYPE_FULL_REDUCE, TYPE_DISCOUNT,
            TYPE_FLASH_SALE, TYPE_REGISTER, TYPE_PURCHASE, TYPE_MERCHANT_INCENTIVE);

    /** 计价行（订单行快照：商品/商户/行金额）——由集成层构造后传入行感知计价 */
    public static class Line {
        private final Long prodId;
        private final Long merId;
        private final BigDecimal lineAmount;

        public Line(Long prodId, Long merId, BigDecimal lineAmount) {
            this.prodId = prodId;
            this.merId = merId;
            this.lineAmount = lineAmount;
        }

        public Long getProdId() { return prodId; }
        public Long getMerId() { return merId; }
        public BigDecimal getLineAmount() { return lineAmount; }
    }

    /** 行感知计价结果：优惠金额 + 实际生效的活动标题（结算页展示"已参与 XX 活动"） */
    public static class PromoResult {
        private final BigDecimal discount;
        private final String appliedTitle;

        public PromoResult(BigDecimal discount, String appliedTitle) {
            this.discount = discount == null ? BigDecimal.ZERO : discount;
            this.appliedTitle = appliedTitle;
        }

        public BigDecimal getDiscount() { return discount; }
        public String getAppliedTitle() { return appliedTitle; }
    }

    /**
     * 计价口径：促销满减/折扣（含秒杀）——保留给既有调用方（委托行感知计价，无行信息）。
     */
    public BigDecimal calculateDiscount(BigDecimal orderAmount, Long orderIndId) {
        return calculateDiscountDetailed(orderAmount, orderIndId, null).getDiscount();
    }

    /**
     * 行感知促销计价（核心）：平台促销与商户促销取优，不叠加（沿用"促销 vs 会员取优、同源取优"互斥不变式）。
     *  - 平台促销（mer_id 为空，scope all/ind）：按订单总额计 promoBenefit，取优惠最大者；
     *  - 商户促销（scope=prod、mer_id 非空、审核通过、在时间窗内）：按命中行金额计优惠
     *    （full_reduce 门槛比行金额合计；discount/flash_sale 按率计算、benefitAmount 封顶）。
     * @param orderAmount 订单原价合计
     * @param orderIndId  订单行业（平台行业限定促销用）
     * @param lines       订单行快照（可空=无行信息，商户促销跳过）
     */
    public PromoResult calculateDiscountDetailed(BigDecimal orderAmount, Long orderIndId, List<Line> lines) {
        BigDecimal amount = orderAmount == null ? BigDecimal.ZERO : orderAmount;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return new PromoResult(BigDecimal.ZERO, null);
        LocalDateTime now = LocalDateTime.now();
        BigDecimal platformBest = BigDecimal.ZERO;
        String platformTitle = null;
        BigDecimal merchantBest = BigDecimal.ZERO;
        String merchantTitle = null;
        for (Campaign c : effectiveCampaigns()) {
            if (!c.isPromoType()) continue;
            if (c.getMerId() == null) {
                // 平台促销（scope all/ind）：按订单总额
                if (!c.applicable(orderIndId)) continue;
                BigDecimal threshold = c.getThreshold() == null ? BigDecimal.ZERO : c.getThreshold();
                if (amount.compareTo(threshold) < 0) continue;
                BigDecimal benefit = promoBenefit(c, amount);
                if (benefit != null && benefit.compareTo(platformBest) > 0) {
                    platformBest = benefit;
                    platformTitle = c.getTitle();
                }
            } else {
                // 商户促销（scope=prod）：审核通过 + 按命中行金额
                if (!SCOPE_PROD.equals(c.getScope())) continue;
                if (c.getReviewStatus() == null || c.getReviewStatus() != 1) continue;
                if (!c.effectiveNow(now)) continue;
                BigDecimal lineAmount = merchantLineAmount(c, lines);
                if (lineAmount.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal threshold = c.getThreshold() == null ? BigDecimal.ZERO : c.getThreshold();
                if (lineAmount.compareTo(threshold) < 0) continue;
                BigDecimal benefit = promoBenefit(c, lineAmount);
                if (benefit != null && benefit.compareTo(merchantBest) > 0) {
                    merchantBest = benefit;
                    merchantTitle = c.getTitle();
                }
            }
        }
        // 互斥取优：平台与商户促销不叠加，取优惠更大者，appliedTitle 取对应者
        if (merchantBest.compareTo(platformBest) > 0) {
            return new PromoResult(merchantBest, merchantTitle);
        }
        return new PromoResult(platformBest, platformTitle);
    }

    /** 商户促销命中的行金额合计（行商户匹配 + 商品在 prod_ids 内；无行信息返回 0） */
    private BigDecimal merchantLineAmount(Campaign c, List<Line> lines) {
        if (lines == null || lines.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l == null || l.getLineAmount() == null) continue;
            if (!c.getMerId().equals(l.getMerId())) continue;
            if (!c.prodIdSet().contains(l.getProdId())) continue;
            sum = sum.add(l.getLineAmount());
        }
        return sum;
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

    /** 生效活动（上架 + 审核通过 + 时间窗内；商户促销待审/驳回不出现在任何用户侧口径），缓存供同类多次读取 */
    private List<Campaign> effectiveCampaigns() {
        LocalDateTime now = LocalDateTime.now();
        return campaignMapper.selectList(Wrappers.<Campaign>lambdaQuery()
                .eq(Campaign::getStatus, 1)
                .eq(Campaign::getReviewStatus, 1)
                .and(w -> w.isNull(Campaign::getStartTime).or().le(Campaign::getStartTime, now))
                .and(w -> w.isNull(Campaign::getEndTime).or().ge(Campaign::getEndTime, now))
                .orderByDesc(Campaign::getCampaignId));
    }

    // ==================== 用户端：活动广场 ====================

    /** 用户端活动列表（tab: all/coupon/promo/incentive；merchant_incentive 不对用户展示；promo tab 混入商户促销） */
    public List<Map<String, Object>> userOngoing(Long userId, String tab) {
        List<Campaign> acts = effectiveCampaigns().stream()
                .filter(c -> !TYPE_MERCHANT_INCENTIVE.equals(c.getType()))
                .filter(Campaign::reviewApproved) // 商户促销仅审核通过的混入（平台活动恒通过）
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
        Map<Long, String> merNames = merchantNames(acts);
        return acts.stream()
                .filter(c -> matchTab(c, tab))
                .map(c -> toUserView(c, myCouponCounts.getOrDefault(c.getCampaignId(), 0L),
                        joinedIds.contains(c.getCampaignId()), indNames, merNames))
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
        Map<Long, String> merNames = merchantNames(List.of(c));
        return toUserView(c, claimed, joined, indNames, merNames);
    }

    /** 统一用户视图（字段口径与列表/详情一致） */
    private Map<String, Object> toUserView(Campaign c, long myCouponCount, boolean joined,
                                           Map<Long, String> indNames, Map<Long, String> merNames) {
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
        // 商户促销补充商户名称（平台活动为 null）
        m.put("merName", c.getMerId() == null ? null : merNames.get(c.getMerId()));
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

    // ==================== 用户端：领券中心（F2） ====================

    /**
     * 领券中心券池（F2）：生效中的 coupon 类活动聚合，含已领数量与剩余配额。
     * 复用 userOngoing(tab=coupon) 口径（同源过滤 + 领券状态字段），不新增查询语义；
     * 商户促销/激励活动不出现在券池（matchTab 只放行 coupon 类型）。
     */
    public List<Map<String, Object>> couponCenter(Long userId) {
        return userOngoing(userId, "coupon");
    }

    /**
     * 批量领取优惠券（领券中心一键领取，F2）：循环复用 claimCoupon 单券原子逻辑
     * （claimOne 条件 UPDATE + lockById 行锁），逐券返回 {campaignId, ok, reason}，部分成功可接受。
     *  - 整批单事务：本方法 @Transactional 提供事务边界（claimCoupon 为自调用，其 @Transactional
     *    不经代理生效，行锁/原子扣减均运行在本事务内，串行化语义不变）；
     *  - 活动 ID 先去重再升序：行锁按固定顺序获取，避免并发批量领取交叉加锁死锁；
     *  - 单券失败（已抢完/达限领上限/活动结束）只记 reason 不中断其余券，结果按入参去重后的
     *    升序返回；重复事件不会重复落库。
     *  - 防重口径沿用现状：perUserLimit 允许同活动多张，不补 (campaign_id,user_id) 唯一键
     *    （activity-plan.md §P1.3，硬防重转 openQuestion 7）。
     */
    @Transactional
    public List<Map<String, Object>> batchClaim(Long userId, List<Long> campaignIds) {
        if (userId == null) throw new BusinessException("请先登录");
        if (campaignIds == null || campaignIds.isEmpty()) throw new BusinessException("请选择要领取的优惠券");
        List<Long> ids = campaignIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("campaignId", id);
            try {
                claimCoupon(userId, id);
                r.put("ok", true);
                r.put("reason", null);
            } catch (BusinessException e) {
                // 部分成功可接受：单券失败原因（已抢完/达限领上限/活动不存在或已结束）随券返回
                r.put("ok", false);
                r.put("reason", e.getMessage());
            } catch (Exception e) {
                r.put("ok", false);
                r.put("reason", "领取失败，请重试");
            }
            results.add(r);
        }
        return results;
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
        try {
            participantMapper.insert(p);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发兜底：唯一键 uk_cp_campaign_user(campaign_id, user_id) 已有记录 → 幂等返回（不重复发分）
            return;
        }
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
        return quoteCoupons(userId, total, indId, null);
    }

    /**
     * 结算可用券试算（行感知重载）：scope=prod 店铺券按"订单行包含该商户商品即可用"过滤，
     * 门槛仍按订单总额；all/ind 券语义零变化。
     * @param lines 订单行快照（商品/商户/行金额），由集成层按购物车重算
     */
    public List<Map<String, Object>> quoteCoupons(Long userId, BigDecimal total, Long indId, List<Line> lines) {
        List<UserCoupon> unused = userCouponMapper.selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getUserId, userId).eq(UserCoupon::getStatus, 0)
                .orderByDesc(UserCoupon::getId));
        if (unused.isEmpty()) return List.of();
        Map<Long, Campaign> campaigns = campaignsByIds(
                unused.stream().map(UserCoupon::getCampaignId).collect(Collectors.toSet()));
        Map<Long, String> indNames = industryNames(new ArrayList<>(campaigns.values()));
        Map<Long, String> merNames = merchantNames(new ArrayList<>(campaigns.values()));
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
            // 店铺券补商户名（前端展示"限 XX 店铺可用"）；all/ind 券无商户名（口径同 promosForProduct）
            m.put("merName", SCOPE_PROD.equals(c.getScope()) && c.getMerId() != null
                    ? merNames.get(c.getMerId()) : null);
            m.put("endTime", c.getEndTime());
            boolean applicable = c.effectiveNow(LocalDateTime.now()) && couponApplicable(c, indId, lines)
                    && total.compareTo(c.getThreshold() == null ? BigDecimal.ZERO : c.getThreshold()) >= 0;
            m.put("applicable", applicable);
            m.put("benefit", applicable ? couponBenefit(c, total) : BigDecimal.ZERO);
            result.add(m);
        }
        return result;
    }

    /** 券适用范围：all 全场 / ind 行业 / prod 店铺券（订单行包含该商户商品即可用，门槛仍按订单总额） */
    private boolean couponApplicable(Campaign c, Long indId, List<Line> lines) {
        if (SCOPE_PROD.equals(c.getScope())) {
            if (c.getMerId() == null || lines == null) return false;
            return lines.stream().anyMatch(l -> l != null && c.getMerId().equals(l.getMerId()));
        }
        return c.applicable(indId);
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
        return applyCouponForOrder(userId, couponId, total, orderIndId, payableBeforeCoupon, orderNo, null);
    }

    /**
     * 结算核销优惠券（行感知重载）：scope=prod 店铺券要求订单行包含该商户商品，
     * 不满足直接拒绝核销（BizException）；all/ind 券语义零变化。
     * 券状态仅区分 0 未使用 / 1 已使用（全链路无人写入 status=2，"已过期"由下方
     * effectiveNow 活动时间窗校验统一拦截，不落库过期态）。
     */
    @Transactional
    public BigDecimal applyCouponForOrder(Long userId, Long couponId, BigDecimal total,
                                          Long orderIndId, BigDecimal payableBeforeCoupon, String orderNo,
                                          List<Line> lines) {
        if (couponId == null) return BigDecimal.ZERO;
        UserCoupon uc = userCouponMapper.selectById(couponId);
        if (uc == null || !userId.equals(uc.getUserId())) throw new BusinessException("优惠券不存在");
        if (uc.getStatus() != null && uc.getStatus() == 1) throw new BusinessException("优惠券已被使用");
        Campaign c = campaignMapper.selectById(uc.getCampaignId());
        if (c == null || !TYPE_COUPON.equals(c.getType()) || !c.effectiveNow(LocalDateTime.now())) {
            throw new BusinessException("优惠券所在活动已结束");
        }
        BigDecimal threshold = c.getThreshold() == null ? BigDecimal.ZERO : c.getThreshold();
        if (total.compareTo(threshold) < 0) {
            throw new BusinessException("订单金额未满足券门槛（满 ¥" + threshold.stripTrailingZeros().toPlainString() + " 可用）");
        }
        if (SCOPE_PROD.equals(c.getScope())) {
            // 店铺券：订单行必须包含该商户的商品
            if (c.getMerId() == null || lines == null
                    || lines.stream().noneMatch(l -> l != null && c.getMerId().equals(l.getMerId()))) {
                throw new BusinessException("该店铺券仅限购买对应商户商品时使用");
            }
        } else if (!c.applicable(orderIndId)) {
            throw new BusinessException("该优惠券不适用当前订单行业");
        }
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

    /**
     * 释放订单占用的优惠券（订单取消/全额退款回退，幂等：仅回退本单核销记录，重复调用匹配 0 行）。
     * 必须用 UpdateWrapper 显式 SET used_time=NULL/order_no=NULL——MP 默认字段策略 NOT_NULL
     * 会跳过 updateById 写入的 null 字段，实体 setter 置 null 不落库（核销痕迹清不掉）。
     * WHERE 保留 order_no 等值 + status=1：回退集合在 SQL 层就限定为本单已核销券。
     */
    @Transactional
    public void releaseCoupon(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return;
        userCouponMapper.update(null, Wrappers.<UserCoupon>update()
                .eq("order_no", orderNo)
                .eq("status", 1)
                .set("status", 0)
                .set("used_time", null)
                .set("order_no", null));
    }

    // ==================== 消费有礼（订单支付事件） ====================

    /**
     * 消费有礼：订单支付事件触发，自动参与所有生效中的 purchase 活动（幂等）。
     * 时间窗口径与 effectiveCampaigns 一致：start_time 未开场的不参与（isNull OR le now），
     * end_time 已结束的不参与；并发下插入撞唯一键 uk_cp_campaign_user 视为已参与（幂等返回）。
     */
    public void autoJoinPurchase(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Campaign> purchaseActs = campaignMapper.selectList(Wrappers.<Campaign>lambdaQuery()
                .eq(Campaign::getType, TYPE_PURCHASE)
                .eq(Campaign::getStatus, 1)
                .and(w -> w.isNull(Campaign::getStartTime).or().le(Campaign::getStartTime, now))
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
                try {
                    participantMapper.insert(p);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // 并发兜底：唯一键 uk_cp_campaign_user(campaign_id, user_id) 已有记录 → 已参与，跳过发分
                    continue;
                }
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
        try {
            campaignMerchantMapper.insert(cm);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发兜底：唯一键 uk_campaign_mer(campaign_id, mer_id) 已有报名记录 → 幂等返回
        }
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

    // ==================== 商户端：自建促销（平台审核制） ====================

    /**
     * 商户创建店铺促销（满减/折扣/秒杀，指定本商户商品）：review_status=0 待平台审核、
     * 默认下架，审核通过后由商户上架生效。所选商品越权（非本商户商品）直接拒绝。
     */
    @Transactional
    public Campaign merchantCreate(Long userId, MerchantPromoDTO dto) {
        Merchant m = mustMerchant(userId);
        if (dto == null) throw new BusinessException("参数不能为空");
        if (!MERCHANT_PROMO_TYPES.contains(dto.getType())) {
            throw new BusinessException("促销类型仅支持满减/折扣/限时秒杀");
        }
        if (dto.getProdIds() == null || dto.getProdIds().isEmpty()) {
            throw new BusinessException("请选择参与促销的商品");
        }
        List<Long> prodIds = dto.getProdIds().stream().filter(Objects::nonNull).distinct().toList();
        if (prodIds.isEmpty()) throw new BusinessException("请选择参与促销的商品");
        List<Product> products = productMapper.selectBatchIds(prodIds);
        if (products == null || products.size() != prodIds.size()) {
            throw new BusinessException("所选商品不存在或已下架");
        }
        for (Product p : products) {
            if (p.getMerId() == null || !p.getMerId().equals(m.getMerId())) {
                throw new BusinessException("只能为本商户的商品创建促销: " + p.getTitle());
            }
        }
        Campaign c = new Campaign();
        c.setTitle(dto.getTitle());
        c.setType(dto.getType());
        c.setDescription(dto.getDescription());
        c.setAccent(dto.getAccent() == null || dto.getAccent().isBlank() ? "blue" : dto.getAccent());
        c.setStartTime(dto.getStartTime());
        c.setEndTime(dto.getEndTime());
        c.setThreshold(dto.getThreshold());
        c.setBenefitAmount(dto.getBenefitAmount());
        c.setDiscountRate(dto.getDiscountRate());
        c.setScope(SCOPE_PROD);
        c.setProdIds(prodIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        c.setMerId(m.getMerId());
        c.setReviewStatus(0); // 待平台审核
        c.setStatus(0);       // 默认下架，审核通过后由商户上架
        c.setPerUserLimit(1);
        c.setTotalQuota(0);
        c.setClaimedCount(0);
        c.setCreateTime(LocalDateTime.now());
        c.setUpdateTime(LocalDateTime.now());
        validateCampaign(c); // 类型化字段口径与平台创建一致（满减必填金额/折扣率 0~1/秒杀必配时间窗）
        campaignMapper.insert(c);
        return c;
    }

    /** 本商户自建促销列表（含全部字段与审核状态） */
    public List<Campaign> merchantMy(Long userId) {
        Merchant m = mustMerchant(userId);
        return campaignMapper.selectList(Wrappers.<Campaign>lambdaQuery()
                .eq(Campaign::getMerId, m.getMerId())
                .orderByDesc(Campaign::getCampaignId));
    }

    /**
     * 本商户促销上/下架切换（越权拒绝；上架要求平台审核通过）。
     * 原子切换（条件携带商户归属，防并发越权覆盖），返回新 status。
     */
    public int merchantToggle(Long userId, Long campaignId) {
        Merchant m = mustMerchant(userId);
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null || c.getMerId() == null || !c.getMerId().equals(m.getMerId())) {
            throw new BusinessException("无权操作该活动");
        }
        int target = (c.getStatus() != null && c.getStatus() == 1) ? 0 : 1;
        if (target == 1 && (c.getReviewStatus() == null || c.getReviewStatus() != 1)) {
            throw new BusinessException("活动尚未通过平台审核，无法上架");
        }
        int affected = campaignMapper.update(null, Wrappers.<Campaign>lambdaUpdate()
                .eq(Campaign::getCampaignId, campaignId)
                .eq(Campaign::getMerId, m.getMerId())
                .set(Campaign::getStatus, target)
                .set(Campaign::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) throw new BusinessException("活动状态切换失败，请重试");
        return target;
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

    /** 管理端创建（校验类型化字段；默认下架，由"上架"动作对外发布；平台活动审核状态恒通过） */
    public Campaign adminCreate(Campaign c) {
        validateCampaign(c);
        if (c.getStatus() == null) c.setStatus(0);
        if (c.getClaimedCount() == null) c.setClaimedCount(0);
        if (c.getScope() == null || c.getScope().isBlank()) c.setScope(SCOPE_ALL);
        if (c.getReviewStatus() == null) c.setReviewStatus(1); // 平台活动不经审核
        c.setCreateTime(LocalDateTime.now());
        c.setUpdateTime(LocalDateTime.now());
        campaignMapper.insert(c);
        return c;
    }

    /**
     * 管理端更新：条件更新仅 SET 目标列（名称/描述/主题色/时间窗/规则列），绝不写回
     * claimed_count 等计数字段与 review_status/review_note/mer_id/prod_ids（各归各的入口），
     * 消除全实体写回把并发计数清零/覆盖审核状态的竞态。状态上下架走 adminToggle。
     */
    public Campaign adminUpdate(Long campaignId, Campaign in) {
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null) throw new BusinessException("活动不存在");
        in.setCampaignId(campaignId);
        validateCampaign(in);
        in.setClaimedCount(c.getClaimedCount()); // 仅回显真实计数（响应契约），不参与写库
        in.setCreateTime(c.getCreateTime());
        in.setUpdateTime(LocalDateTime.now());
        campaignMapper.update(null, Wrappers.<Campaign>lambdaUpdate()
                .eq(Campaign::getCampaignId, campaignId)
                .set(Campaign::getTitle, in.getTitle())
                .set(Campaign::getType, in.getType())
                .set(Campaign::getDescription, in.getDescription())
                .set(Campaign::getAccent, in.getAccent())
                .set(Campaign::getStartTime, in.getStartTime())
                .set(Campaign::getEndTime, in.getEndTime())
                .set(Campaign::getThreshold, in.getThreshold())
                .set(Campaign::getBenefitAmount, in.getBenefitAmount())
                .set(Campaign::getDiscountRate, in.getDiscountRate())
                .set(Campaign::getScope, in.getScope())
                .set(Campaign::getIndId, in.getIndId())
                .set(Campaign::getPerUserLimit, in.getPerUserLimit())
                .set(Campaign::getTotalQuota, in.getTotalQuota())
                .set(Campaign::getRewardPoints, in.getRewardPoints())
                .set(Campaign::getMerTargetAmount, in.getMerTargetAmount())
                .set(Campaign::getMerRewardPoints, in.getMerRewardPoints())
                .set(Campaign::getJoinable, in.getJoinable())
                .set(Campaign::getUpdateTime, in.getUpdateTime()));
        return in;
    }

    /** 管理端创建（DTO 入参，Controller 不直接依赖实体） */
    public Campaign adminCreate(com.lanlink.shopping.module.campaign.dto.CampaignUpsertRequest req) {
        return adminCreate(req.toEntity());
    }

    /** 管理端更新（DTO 入参） */
    public Campaign adminUpdate(Long campaignId, com.lanlink.shopping.module.campaign.dto.CampaignUpsertRequest req) {
        return adminUpdate(campaignId, req.toEntity());
    }

    /** 上架/下架：条件更新仅 SET status/update_time，不整实体写回（防覆盖 claimed_count 等计数列） */
    public void adminToggle(Long campaignId, int status) {
        if (status != 0 && status != 1) throw new BusinessException("非法状态值");
        int affected = campaignMapper.update(null, Wrappers.<Campaign>lambdaUpdate()
                .eq(Campaign::getCampaignId, campaignId)
                .set(Campaign::getStatus, status)
                .set(Campaign::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) throw new BusinessException("活动不存在");
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

    // ==================== 管理端：商户促销审核 ====================

    /** 商户活动审核分页（仅商户自建活动；reviewStatus 可空=全部） */
    public Page<Campaign> merchantAuditPage(long page, long size, Integer reviewStatus) {
        return campaignMapper.selectPage(new Page<>(page, size), Wrappers.<Campaign>lambdaQuery()
                .isNotNull(Campaign::getMerId)
                .eq(reviewStatus != null, Campaign::getReviewStatus, reviewStatus)
                .orderByDesc(Campaign::getCampaignId));
    }

    /**
     * 审核商户促销：通过→review_status=1、驳回→review_status=2（驳回必须填写意见），审核意见随单落库。
     * 条件更新仅 SET review_status/review_note/update_time，不整实体写回（防并发覆盖 status 等其他列）；
     * 活动已被删除时影响行数=0 同样报"商户活动不存在"。
     */
    public Campaign merchantReview(Long campaignId, boolean approved, String note) {
        Campaign c = campaignMapper.selectById(campaignId);
        if (c == null || c.getMerId() == null) throw new BusinessException("商户活动不存在");
        String trimmed = note == null ? null : note.trim();
        if (!approved && (trimmed == null || trimmed.isBlank())) {
            throw new BusinessException("驳回时必须填写审核意见");
        }
        if (trimmed != null && trimmed.length() > 200) {
            throw new BusinessException("审核意见长度不能超过 200 字符");
        }
        int affected = campaignMapper.update(null, Wrappers.<Campaign>lambdaUpdate()
                .eq(Campaign::getCampaignId, campaignId)
                .set(Campaign::getReviewStatus, approved ? 1 : 2)
                .set(Campaign::getReviewNote, trimmed)
                .set(Campaign::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) throw new BusinessException("商户活动不存在");
        c.setReviewStatus(approved ? 1 : 2);
        c.setReviewNote(trimmed);
        return c;
    }

    /** 类型化字段校验（口径与前端表单一致；长度护栏与 schema 列宽对齐：title 100 / description 500 / prod_ids 500） */
    private void validateCampaign(Campaign c) {
        if (c.getTitle() == null || c.getTitle().isBlank()) throw new BusinessException("活动名称不能为空");
        if (c.getTitle().length() > 100) throw new BusinessException("活动名称长度不能超过 100 字符");
        if (c.getType() == null || !ALL_TYPES.contains(c.getType())) throw new BusinessException("活动类型非法");
        if (c.getDescription() != null && c.getDescription().length() > 500) {
            throw new BusinessException("活动描述长度不能超过 500 字符");
        }
        if (c.getProdIds() != null && !c.getProdIds().isBlank()) {
            if (c.prodIdSet().size() > 50) throw new BusinessException("参与促销的商品数量不能超过 50 个");
            if (c.getProdIds().length() > 490) {
                throw new BusinessException("参与促销的商品列表过长（拼接后不能超过 490 字符）");
            }
        }
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
                // 先按 DECIMAL(4,3) 列精度取整（三位小数）再判开区间 (0,1)：0.9995 取整为 1.000 应拒绝，
                // 避免"Java 校验通过但落库被列精度收整为 1.000"的精度边界逃逸
                if (c.getDiscountRate() != null) {
                    c.setDiscountRate(c.getDiscountRate().setScale(3, RoundingMode.HALF_UP));
                }
                if (c.getDiscountRate() == null || c.getDiscountRate().signum() <= 0
                        || c.getDiscountRate().compareTo(BigDecimal.ONE) >= 0) {
                    throw new BusinessException("折扣率必须在 0~1 之间且最多三位小数（0.95 = 95 折）");
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

    /**
     * 商品详情页促销（公开浏览）：生效中命中该商品的促销数组——
     * 平台促销（scope all/ind）+ 商户促销（scope=prod、审核通过），带商户名称（平台活动 merName=null）。
     */
    public List<Map<String, Object>> promosForProduct(Long prodId) {
        if (prodId == null) return List.of();
        Product p = productMapper.selectById(prodId);
        if (p == null) return List.of();
        List<Campaign> hits = effectiveCampaigns().stream()
                .filter(Campaign::isPromoType)
                // 商户促销仅 scope=prod 且审核通过（平台活动 mer_id 为空不受限）
                .filter(c -> c.getMerId() == null
                        || (SCOPE_PROD.equals(c.getScope()) && c.reviewApproved()))
                .filter(c -> c.applicableForProduct(prodId, p.getIndId()))
                .toList();
        if (hits.isEmpty()) return List.of();
        Map<Long, String> merNames = merchantNames(hits);
        return hits.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaignId", c.getCampaignId());
            m.put("title", c.getTitle());
            m.put("type", c.getType());
            m.put("accent", c.getAccent());
            m.put("threshold", c.getThreshold());
            m.put("benefitAmount", c.getBenefitAmount());
            m.put("discountRate", c.getDiscountRate());
            m.put("merName", c.getMerId() == null ? null : merNames.get(c.getMerId()));
            m.put("startTime", c.getStartTime());
            m.put("endTime", c.getEndTime());
            return m;
        }).toList();
    }

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

    /** 商户名称映射（商户 → 所属企业名称，取名先例同 MerchantChatService；平台活动无商户名） */
    private Map<Long, String> merchantNames(List<Campaign> acts) {
        Set<Long> merIds = acts.stream().map(Campaign::getMerId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (merIds.isEmpty() || merchantMapper == null) return Map.of();
        Map<Long, Long> entByMer = new HashMap<>();
        for (Merchant m : merchantMapper.selectBatchIds(merIds)) {
            if (m.getMerId() != null) entByMer.put(m.getMerId(), m.getEntId());
        }
        Set<Long> entIds = entByMer.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> entNames = new HashMap<>();
        if (!entIds.isEmpty() && enterpriseMapper != null) {
            for (Enterprise e : enterpriseMapper.selectBatchIds(entIds)) {
                if (e.getEntId() != null) entNames.put(e.getEntId(), e.getName());
            }
        }
        Map<Long, String> out = new HashMap<>();
        for (Long merId : merIds) {
            Long entId = entByMer.get(merId);
            String name = entId == null ? null : entNames.get(entId);
            out.put(merId, name == null || name.isBlank() ? "商家 #" + merId : name);
        }
        return out;
    }

    private final CampaignMapper campaignMapper;
    private final UserCouponMapper userCouponMapper;
    private final CampaignParticipantMapper participantMapper;
    private final CampaignMerchantMapper campaignMerchantMapper;
    private final MerchantMapper merchantMapper;
    private final IndustryMapper industryMapper;
    private final MembershipService membershipService;
    private final ProductMapper productMapper;
    private final EnterpriseMapper enterpriseMapper;

    /** 兼容构造（既有单测口径）：商品/企业名称映射依赖缺省，for-product 与商户名展示能力降级 */
    public CampaignService(CampaignMapper campaignMapper, UserCouponMapper userCouponMapper,
                           CampaignParticipantMapper participantMapper, CampaignMerchantMapper campaignMerchantMapper,
                           MerchantMapper merchantMapper, IndustryMapper industryMapper,
                           MembershipService membershipService) {
        this(campaignMapper, userCouponMapper, participantMapper, campaignMerchantMapper,
                merchantMapper, industryMapper, membershipService, null, null);
    }

    @Autowired
    public CampaignService(CampaignMapper campaignMapper, UserCouponMapper userCouponMapper,
                           CampaignParticipantMapper participantMapper, CampaignMerchantMapper campaignMerchantMapper,
                           MerchantMapper merchantMapper, IndustryMapper industryMapper,
                           MembershipService membershipService, ProductMapper productMapper,
                           EnterpriseMapper enterpriseMapper) {
        this.campaignMapper = campaignMapper;
        this.userCouponMapper = userCouponMapper;
        this.participantMapper = participantMapper;
        this.campaignMerchantMapper = campaignMerchantMapper;
        this.merchantMapper = merchantMapper;
        this.industryMapper = industryMapper;
        this.membershipService = membershipService;
        this.productMapper = productMapper;
        this.enterpriseMapper = enterpriseMapper;
    }
}
