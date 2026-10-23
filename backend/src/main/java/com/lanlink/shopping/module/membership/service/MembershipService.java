package com.lanlink.shopping.module.membership.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.membership.entity.MemberCard;
import com.lanlink.shopping.module.membership.entity.MemberLevel;
import com.lanlink.shopping.module.membership.entity.MemberPointLog;
import com.lanlink.shopping.module.membership.mapper.MemberCardMapper;
import com.lanlink.shopping.module.membership.mapper.MemberLevelMapper;
import com.lanlink.shopping.module.membership.mapper.MemberPointLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.lanlink.shopping.module.membership.constant.MemberRule;

/**
 * 会员系统服务（营销中台·独立模块）。
 * 对外能力：getOrCreateCard / earnGrowth / levelDiscountRate / 积分流水。
 * 成长值由订单支付事件（MembershipOrderListener）触发累计，模块间通过事件解耦。
 */
@Service
public class MembershipService {

    private final MemberCardMapper cardMapper;
    private final MemberLevelMapper levelMapper;
    private final MemberPointLogMapper pointLogMapper;
    private final SubscriptionService subscriptionService;

    public MembershipService(MemberCardMapper cardMapper, MemberLevelMapper levelMapper,
                             MemberPointLogMapper pointLogMapper, SubscriptionService subscriptionService) {
        this.cardMapper = cardMapper;
        this.levelMapper = levelMapper;
        this.pointLogMapper = pointLogMapper;
        this.subscriptionService = subscriptionService;
    }

    /** 获取或创建用户会员卡（user_id 唯一） */
    @Transactional
    public MemberCard getOrCreateCard(Long userId) {
        if (userId == null) throw new BusinessException("请先登录");
        MemberCard card = cardMapper.selectOne(Wrappers.<MemberCard>lambdaQuery()
                .eq(MemberCard::getUserId, userId));
        if (card == null) {
            MemberLevel base = levelMapper.selectOne(Wrappers.<MemberLevel>lambdaQuery()
                    .eq(MemberLevel::getMinGrowth, 0).orderByAsc(MemberLevel::getSort).last("LIMIT 1"));
            card = new MemberCard();
            card.setUserId(userId);
            card.setLevelId(base == null ? 1L : base.getLevelId());
            card.setGrowth(0);
            card.setPoints(0);
            card.setUpdateTime(LocalDateTime.now());
            cardMapper.insert(card);
        }
        return card;
    }

    /** 累计成长值 + 积分（订单支付后调用；幂等由业务方保证）。multiplier 为积分倍数（VIP=2，普通=1）。 */
    @Transactional
    public void earnGrowth(Long userId, BigDecimal orderAmount, String orderNo, int multiplier) {
        MemberCard card = getOrCreateCard(userId);
        int growth = orderAmount == null ? 0 : orderAmount.intValue();
        int points = orderAmount == null ? 0 : orderAmount.intValue() * Math.max(1, multiplier);
        card.setGrowth(card.getGrowth() == null ? 0 : card.getGrowth() + growth);
        card.setPoints(card.getPoints() == null ? 0 : card.getPoints() + points);
        card.setLevelId(levelForGrowth(card.getGrowth(), userId));
        card.setUpdateTime(LocalDateTime.now());
        cardMapper.updateById(card);

        MemberPointLog log = new MemberPointLog();
        log.setUserId(userId);
        log.setChangeType("earn_consume");
        log.setChangeVal(points);
        log.setRefOrderNo(orderNo);
        log.setRemark(multiplier > 1 ? "订单消费获得积分（VIP双倍）" : "订单消费获得积分");
        log.setCreateTime(LocalDateTime.now());
        pointLogMapper.insert(log);
    }

    // ===== 积分抵现（参考 mall/litemall 下单积分抵扣设计） =====

    /**
     * 抵现试算：计算指定积分数在给定订单金额下实际可抵扣的积分与金额。
     * 规则：1000 积分 = 1 元，抵扣最小单位 1 元（不足 1000 积分的零头不抵扣）；
     *       不超过积分余额；不超过单笔上限（应付金额的 REDEEM_MAX_PERCENT%，向下取整到整元）。
     * 自动计算本单可抵扣的最大积分：上限金额 × 1000。
     * @return {points: 实际抵扣积分, amount: 抵扣金额, maxAmount: 本单可抵上限金额(整元),
     *          maxPoints: 本单可抵最大积分, balance: 当前积分余额, payable: 抵扣后实付金额}
     */
    public Map<String, Object> redeemQuote(Long userId, Integer points, BigDecimal orderAmount) {
        MemberCard card = getOrCreateCard(userId);
        int balance = card.getPoints() == null ? 0 : card.getPoints();
        // 单笔上限金额：应付金额的 10%，向下取整到整元（抵扣最小单位 1 元）
        BigDecimal maxAmount = orderAmount == null ? BigDecimal.ZERO
                : orderAmount.multiply(BigDecimal.valueOf(MemberRule.REDEEM_MAX_PERCENT))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        int maxPoints = MemberRule.amountToRedeemPoints(maxAmount);
        // 实际抵扣积分 = min(请求积分, 余额, 上限积分)，再向下归整到 1000 的整数倍（零头不抵扣）
        int want = points == null ? 0 : Math.min(points, balance);
        want = MemberRule.usablePoints(Math.min(want, maxPoints));
        BigDecimal amount = MemberRule.pointsToAmount(want);
        BigDecimal payable = orderAmount == null ? BigDecimal.ZERO
                : orderAmount.subtract(amount).max(BigDecimal.ZERO);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", want);
        out.put("amount", amount);
        out.put("maxAmount", maxAmount);
        out.put("maxPoints", maxPoints);
        out.put("balance", balance);
        out.put("payable", payable);
        return out;
    }

    /**
     * 下单抵现：扣减积分并写 redeem_order 流水。
     * 由 OrderService 在结算事务内调用；抵扣积分经 redeemQuote 同口径校验。
     * 规则：1000 积分 = 1 元，整元抵扣；请求积分经归整后为 0（不足 1 元零头/积分不足）时不做抵扣，直接返回 0。
     * @return 实际抵扣金额（元）
     */
    @Transactional
    public BigDecimal redeemPoints(Long userId, int points, BigDecimal orderAmount, String orderNo) {
        if (points <= 0) return BigDecimal.ZERO;
        Map<String, Object> q = redeemQuote(userId, points, orderAmount);
        int actual = ((Number) q.get("points")).intValue();
        BigDecimal amount = (BigDecimal) q.get("amount");
        // 归整后不足 1 元（或余额/上限内无可用积分）：不做抵扣、不扣积分
        if (actual <= 0 || amount.signum() <= 0) return BigDecimal.ZERO;
        MemberCard card = getOrCreateCard(userId);
        card.setPoints(Math.max(0, (card.getPoints() == null ? 0 : card.getPoints()) - actual));
        card.setUpdateTime(LocalDateTime.now());
        cardMapper.updateById(card);

        MemberPointLog log = new MemberPointLog();
        log.setUserId(userId);
        log.setChangeType(MemberRule.CHANGE_REDEEM);
        log.setChangeVal(-actual);
        log.setRefOrderNo(orderNo);
        log.setRemark("下单积分抵现：" + actual + " 积分抵扣 " + amount + " 元（1000 积分 = 1 元）");
        log.setCreateTime(LocalDateTime.now());
        pointLogMapper.insert(log);
        return amount;
    }

    /**
     * 取消返还：订单取消时按 redeem_order 流水返还积分（幂等：已有 refund 流水则跳过）。
     * @return 返还积分数（无抵现记录返回 0）
     */
    @Transactional
    public int refundPoints(Long userId, String orderNo) {
        List<MemberPointLog> redeems = pointLogMapper.selectList(Wrappers.<MemberPointLog>lambdaQuery()
                .eq(MemberPointLog::getUserId, userId)
                .eq(MemberPointLog::getRefOrderNo, orderNo)
                .eq(MemberPointLog::getChangeType, MemberRule.CHANGE_REDEEM));
        if (redeems.isEmpty()) return 0;
        // 幂等：该订单已返还过则跳过
        Long refunded = pointLogMapper.selectCount(Wrappers.<MemberPointLog>lambdaQuery()
                .eq(MemberPointLog::getUserId, userId)
                .eq(MemberPointLog::getRefOrderNo, orderNo)
                .eq(MemberPointLog::getChangeType, MemberRule.CHANGE_REFUND));
        if (refunded != null && refunded > 0) return 0;

        int points = redeems.stream().mapToInt(l -> Math.abs(l.getChangeVal() == null ? 0 : l.getChangeVal())).sum();
        MemberCard card = getOrCreateCard(userId);
        card.setPoints((card.getPoints() == null ? 0 : card.getPoints()) + points);
        card.setUpdateTime(LocalDateTime.now());
        cardMapper.updateById(card);

        MemberPointLog log = new MemberPointLog();
        log.setUserId(userId);
        log.setChangeType(MemberRule.CHANGE_REFUND);
        log.setChangeVal(points);
        log.setRefOrderNo(orderNo);
        log.setRemark("订单取消，积分返还");
        log.setCreateTime(LocalDateTime.now());
        pointLogMapper.insert(log);
        return points;
    }

    /**
     * 退款扣回：全额退款时扣回该订单支付所得积分（幂等：已有 earn_reversal 流水则跳过）。
     * 与 refundPoints（返还抵现积分）方向相反，全额退款时两者都会执行，保证积分账目与订单状态一致。
     * @return 扣回积分数（无所得记录返回 0）
     */
    @Transactional
    public int reverseEarn(Long userId, String orderNo) {
        List<MemberPointLog> earns = pointLogMapper.selectList(Wrappers.<MemberPointLog>lambdaQuery()
                .eq(MemberPointLog::getUserId, userId)
                .eq(MemberPointLog::getRefOrderNo, orderNo)
                .eq(MemberPointLog::getChangeType, "earn_consume"));
        if (earns.isEmpty()) return 0;
        // 幂等：该订单已扣回过则跳过
        Long reversed = pointLogMapper.selectCount(Wrappers.<MemberPointLog>lambdaQuery()
                .eq(MemberPointLog::getUserId, userId)
                .eq(MemberPointLog::getRefOrderNo, orderNo)
                .eq(MemberPointLog::getChangeType, MemberRule.CHANGE_EARN_REVERSAL));
        if (reversed != null && reversed > 0) return 0;

        int points = earns.stream().mapToInt(l -> Math.abs(l.getChangeVal() == null ? 0 : l.getChangeVal())).sum();
        MemberCard card = getOrCreateCard(userId);
        // 扣回所得积分，不扣成负数（积分可能已被消费）
        int deducted = Math.min(points, card.getPoints() == null ? 0 : card.getPoints());
        if (deducted > 0) {
            card.setPoints(card.getPoints() - deducted);
            card.setUpdateTime(LocalDateTime.now());
            cardMapper.updateById(card);
        }

        MemberPointLog log = new MemberPointLog();
        log.setUserId(userId);
        log.setChangeType(MemberRule.CHANGE_EARN_REVERSAL);
        log.setChangeVal(-points);
        log.setRefOrderNo(orderNo);
        log.setRemark("订单全额退款，扣回支付所得积分");
        log.setCreateTime(LocalDateTime.now());
        pointLogMapper.insert(log);
        return points;
    }

    /**
     * 依据成长值匹配最高等级。
     * 会员等级解锁采用「有效订阅 + 成长值」双重条件：无生效订阅时一律回落为普通会员（等级 1），
     * 有生效订阅时才按成长值取最高等级。
     */
    public Long levelForGrowth(int growth, Long userId) {
        // 无生效订阅 → 普通会员（等级1），不论成长值
        if (!subscriptionService.hasActiveSubscription(userId)) return 1L;
        List<MemberLevel> levels = levelMapper.selectList(Wrappers.<MemberLevel>lambdaQuery()
                .orderByDesc(MemberLevel::getMinGrowth));
        for (MemberLevel l : levels) {
            if (growth >= l.getMinGrowth()) return l.getLevelId();
        }
        return levels.isEmpty() ? 1L : levels.get(levels.size() - 1).getLevelId();
    }

    /** 当前等级折扣率（默认 1.0 = 无折扣） */
    public BigDecimal levelDiscountRate(Long userId) {
        MemberCard card = getOrCreateCard(userId);
        MemberLevel level = levelMapper.selectById(card.getLevelId());
        return level == null || level.getDiscountRate() == null ? BigDecimal.ONE : level.getDiscountRate();
    }

    /** 我的会员视图（卡 + 等级 + 积分流水） */
    public Map<String, Object> myView(Long userId) {
        MemberCard card = getOrCreateCard(userId);
        MemberLevel level = levelMapper.selectById(card.getLevelId());
        List<MemberLevel> levels = levelMapper.selectList(Wrappers.<MemberLevel>lambdaQuery()
                .orderByAsc(MemberLevel::getSort));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("card", card);
        out.put("level", level);
        out.put("levels", levels);
        out.put("subscription", subscriptionService.mySubscription(userId));
        // 积分规则（前端展示：获取比例 / 抵现比例 / 单笔上限 / VIP 倍数）
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("pointsPerYuanEarn", MemberRule.POINTS_PER_YUAN_EARN);
        rule.put("redeemPointsPerYuan", MemberRule.REDEEM_POINTS_PER_YUAN);
        rule.put("redeemMaxPercent", MemberRule.REDEEM_MAX_PERCENT);
        rule.put("vipEarnMultiplier", MemberRule.VIP_EARN_MULTIPLIER);
        out.put("rule", rule);
        out.put("pointsLog", pointLogMapper.selectList(Wrappers.<MemberPointLog>lambdaQuery()
                .eq(MemberPointLog::getUserId, userId).orderByDesc(MemberPointLog::getLogId).last("LIMIT 20")));
        return out;
    }

    /** 单条积分流水详情（按用户归属校验，避免越权读取他人流水；不存在返回 null） */
    public MemberPointLog pointLog(Long userId, Long logId) {
        if (userId == null || logId == null) return null;
        return pointLogMapper.selectOne(Wrappers.<MemberPointLog>lambdaQuery()
                .eq(MemberPointLog::getLogId, logId)
                .eq(MemberPointLog::getUserId, userId));
    }

    /** 活动/营销奖励发放积分，不增加成长值；积分流水独立记账。 */
    @Transactional
    public int awardPoints(Long userId, int points, String refNo, String remark) {
        if (points <= 0) return 0;
        // 幂等：同一业务引用号只允许产生一次活动奖励，避免重复事件/重复提交造成积分膨胀。
        if (refNo != null && !refNo.isBlank()) {
            Long exists = pointLogMapper.selectCount(Wrappers.<MemberPointLog>lambdaQuery()
                    .eq(MemberPointLog::getUserId, userId)
                    .eq(MemberPointLog::getChangeType, "activity_reward")
                    .eq(MemberPointLog::getRefOrderNo, refNo));
            if (exists != null && exists > 0) return 0;
        }
        MemberCard card = getOrCreateCard(userId);
        int before = card.getPoints() == null ? 0 : card.getPoints();
        card.setPoints(before + points);
        card.setUpdateTime(LocalDateTime.now());
        cardMapper.updateById(card);

        MemberPointLog pointLog = new MemberPointLog();
        pointLog.setUserId(userId);
        pointLog.setChangeType("activity_reward");
        pointLog.setChangeVal(points);
        pointLog.setRefOrderNo(refNo);
        pointLog.setRemark(remark == null || remark.isBlank() ? "活动奖励积分" : remark.trim());
        pointLog.setCreateTime(LocalDateTime.now());
        pointLogMapper.insert(pointLog);
        return points;
    }

}
