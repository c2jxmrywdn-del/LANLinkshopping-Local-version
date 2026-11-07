package com.lanlink.shopping.module.membership.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.membership.entity.CheckinRecord;
import com.lanlink.shopping.module.membership.entity.MemberCard;
import com.lanlink.shopping.module.membership.entity.MemberLevel;
import com.lanlink.shopping.module.membership.entity.MemberPointLog;
import com.lanlink.shopping.module.membership.mapper.CheckinMapper;
import com.lanlink.shopping.module.membership.mapper.MemberCardMapper;
import com.lanlink.shopping.module.membership.mapper.MemberLevelMapper;
import com.lanlink.shopping.module.membership.mapper.MemberPointLogMapper;
import com.lanlink.shopping.service.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.lanlink.shopping.module.membership.constant.MemberRule;

/**
 * 会员系统服务（营销中台·独立模块）。
 * 对外能力：getOrCreateCard / earnGrowth / levelDiscountRate / 积分流水 / 每日签到。
 * 成长值由订单支付事件（MembershipOrderListener）触发累计，模块间通过事件解耦。
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    private final MemberCardMapper cardMapper;
    private final MemberLevelMapper levelMapper;
    private final MemberPointLogMapper pointLogMapper;
    private final SubscriptionService subscriptionService;
    private final CheckinMapper checkinMapper;

    private volatile IdentityService identityService;
    private volatile ProductMapper productMapper;

    /** 签到奖励档位：基础 10 分，连续第 2 天起每天 +5，封顶 +30（连续 7 天起每日固定 40 分） */
    public static final int CHECKIN_BASE_POINTS = 10;
    public static final int CHECKIN_STREAK_BONUS = 5;
    public static final int CHECKIN_STREAK_BONUS_CAP = 6;

    /** 兼容构造（既有单测口径）：签到 Mapper 缺省，签到能力降级不可用 */
    public MembershipService(MemberCardMapper cardMapper, MemberLevelMapper levelMapper,
                             MemberPointLogMapper pointLogMapper, SubscriptionService subscriptionService) {
        this(cardMapper, levelMapper, pointLogMapper, subscriptionService, null);
    }

    @Autowired
    public MembershipService(MemberCardMapper cardMapper, MemberLevelMapper levelMapper,
                             MemberPointLogMapper pointLogMapper, SubscriptionService subscriptionService,
                             CheckinMapper checkinMapper) {
        this.cardMapper = cardMapper;
        this.levelMapper = levelMapper;
        this.pointLogMapper = pointLogMapper;
        this.subscriptionService = subscriptionService;
        this.checkinMapper = checkinMapper;
    }

    /** 身份识别（D2 双倍积分判据需要）：setter 注入，既有构造器与单测不受影响 */
    @Autowired(required = false)
    public void setIdentityService(IdentityService identityService) {
        this.identityService = identityService;
    }

    /** 商品访问（P1-7 会员价试算）：setter 注入，缺省时试算能力不可用 */
    @Autowired(required = false)
    public void setProductMapper(ProductMapper productMapper) {
        this.productMapper = productMapper;
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

    /** 累计成长值 + 积分（订单支付后调用）。按 orderNo 幂等，重复事件不会重复计分。multiplier 为积分倍数（VIP=2，普通=1）。 */
    @Transactional
    public void earnGrowth(Long userId, BigDecimal orderAmount, String orderNo, int multiplier) {
        // 幂等：同一订单的消费积分只累计一次（事件重放/重复回调安全）
        if (orderNo != null && !orderNo.isBlank()
                && pointLogMapper.selectCount(Wrappers.<MemberPointLog>lambdaQuery()
                .eq(MemberPointLog::getUserId, userId)
                .eq(MemberPointLog::getChangeType, "earn_consume")
                .eq(MemberPointLog::getRefOrderNo, orderNo)) > 0) {
            return;
        }
        getOrCreateCard(userId);
        int growth = orderAmount == null ? 0 : orderAmount.intValue();
        int points = orderAmount == null ? 0 : orderAmount.intValue() * Math.max(1, multiplier);
        // 原子累加：并发发奖（首单有礼/活动激励/消费积分同秒落账）下 read-modify-write 会互相覆盖丢更新
        cardMapper.update(null, Wrappers.<MemberCard>lambdaUpdate()
                .eq(MemberCard::getUserId, userId)
                .setSql("growth = COALESCE(growth, 0) + " + growth)
                .setSql("points = COALESCE(points, 0) + " + points)
                .set(MemberCard::getUpdateTime, LocalDateTime.now()));
        // 等级由累加后的最新成长值决定（读回再判定，防并发读旧值漏升级）
        MemberCard fresh = cardMapper.selectOne(Wrappers.<MemberCard>lambdaQuery()
                .eq(MemberCard::getUserId, userId));
        Long newLevelId = levelForGrowth(fresh == null || fresh.getGrowth() == null ? 0 : fresh.getGrowth(), userId);
        if (fresh != null && (newLevelId == null ? fresh.getLevelId() != null : !newLevelId.equals(fresh.getLevelId()))) {
            cardMapper.update(null, Wrappers.<MemberCard>lambdaUpdate()
                    .eq(MemberCard::getUserId, userId)
                    .set(MemberCard::getLevelId, newLevelId)
                    .set(MemberCard::getUpdateTime, LocalDateTime.now()));
        }

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

    /**
     * 消费积分倍数（D2 拍板口径）：身份 VIP ∪ 有效订阅 取或。
     * 两处同款判据（MembershipOrderListener / MembershipOrderMqConsumer）统一收敛到本方法，防判据漂移。
     * 商家/管理员身份不参与倍数（口径与 IdentityService.identify 一致，维持既有行为）；
     * identityService 未注入（兼容构造）时仅按订阅判定。
     */
    public int multiplierOf(Long userId, Long roleId) {
        if (roleId != null && (roleId == 2L || roleId == 3L)) return 1;
        if (identityService != null && identityService.identify(userId, roleId) == UserIdentity.VIP) {
            return MemberRule.VIP_EARN_MULTIPLIER;
        }
        if (subscriptionService.hasActiveSubscription(userId)) return MemberRule.VIP_EARN_MULTIPLIER;
        return 1;
    }

    /**
     * 会员价试算（P1-7）：按当前成长值「可达档位」（与 levelForGrowth 同口径取最高满足档，非最高档）
     * 返回该商品本单立省金额。无生效订阅时按「开通订阅后可达档位」试算（试算的是订阅解锁的资格）；
     * 可达档位折扣率 ≥1（普通会员档 1.000）时 applicable=false，前端回退为无金额整句。
     * @return {applicable, levelName, discountRate, prodPrice, saving, hasActiveSubscription}
     */
    public Map<String, Object> pricePreview(Long userId, Long prodId) {
        if (productMapper == null) throw new BusinessException("会员价试算暂不可用");
        Product p = productMapper.selectById(prodId);
        if (p == null || p.getPrice() == null) throw new BusinessException("商品不存在");
        MemberCard card = getOrCreateCard(userId);
        int growth = card.getGrowth() == null ? 0 : card.getGrowth();
        // 可达档位：成长值满足的最高档（不经订阅门控——预览的是开通订阅后可解锁的档位资格）
        List<MemberLevel> levels = levelMapper.selectList(Wrappers.<MemberLevel>lambdaQuery()
                .orderByDesc(MemberLevel::getMinGrowth));
        MemberLevel reachable = null;
        for (MemberLevel l : levels) {
            if (growth >= (l.getMinGrowth() == null ? 0 : l.getMinGrowth())) { reachable = l; break; }
        }
        BigDecimal rate = reachable == null || reachable.getDiscountRate() == null
                ? BigDecimal.ONE : reachable.getDiscountRate();
        BigDecimal saving = p.getPrice().multiply(BigDecimal.ONE.subtract(rate)).setScale(2, RoundingMode.HALF_UP);
        boolean applicable = rate.compareTo(BigDecimal.ONE) < 0 && saving.signum() > 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicable", applicable);
        out.put("levelName", reachable == null ? null : reachable.getLevelName());
        out.put("discountRate", rate);
        out.put("prodPrice", p.getPrice());
        out.put("saving", applicable ? saving : BigDecimal.ZERO);
        out.put("hasActiveSubscription", subscriptionService.hasActiveSubscription(userId));
        return out;
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

        MemberPointLog pointLog = new MemberPointLog();
        pointLog.setUserId(userId);
        pointLog.setChangeType("activity_reward");
        pointLog.setChangeVal(points);
        pointLog.setRefOrderNo(refNo);
        pointLog.setRemark(remark == null || remark.isBlank() ? "活动奖励积分" : remark.trim());
        pointLog.setCreateTime(LocalDateTime.now());
        // 先写唯一键流水再累加余额：并发重复发奖时数据库唯一键只会放行一条，
        // 插入失败直接返回 0，避免"先加余额、后撞键"导致积分被重复累加。
        try {
            pointLogMapper.insert(pointLog);
        } catch (DuplicateKeyException e) {
            log.debug("激励重复发放已拦截：userId={}, refNo={}", userId, refNo);
            return 0;
        }
        card.setPoints(before + points);
        card.setUpdateTime(LocalDateTime.now());
        cardMapper.updateById(card);
        return points;
    }

    // ==================== 每日签到 ====================

    /** 签到奖励档位：基础 10 分 + min(连签天数-1, 6) × 5（连续 7 天起每日 40 分封顶） */
    public static int checkinPointsFor(int streak) {
        int bonusSteps = Math.min(Math.max(streak - 1, 0), CHECKIN_STREAK_BONUS_CAP);
        return CHECKIN_BASE_POINTS + bonusSteps * CHECKIN_STREAK_BONUS;
    }

    /**
     * 每日签到（幂等）：今日已签直接返回当日已有记录（code 200）；未签则落 t_checkin
     * （唯一键 uk_checkin_user_date 兜底并发重复签到），streak = 昨天有记录 ? 昨 streak+1 : 1，
     * 积分经 awardPoints(refNo="checkin:yyyy-MM-dd") 幂等落账（重复事件不重复加分）。
     * @return {date:"yyyy-MM-dd", points, streak, signedToday:true}
     */
    @Transactional
    public Map<String, Object> checkin(Long userId) {
        if (userId == null) throw new BusinessException("请先登录");
        if (checkinMapper == null) throw new BusinessException("签到功能暂不可用");
        LocalDate today = LocalDate.now();
        CheckinRecord todayRec = findCheckin(userId, today);
        if (todayRec != null) return checkinView(todayRec); // 今日已签：幂等返回当日已有记录
        CheckinRecord yesterday = findCheckin(userId, today.minusDays(1));
        int streak = yesterday == null || yesterday.getStreak() == null ? 1 : yesterday.getStreak() + 1;
        int points = checkinPointsFor(streak);
        CheckinRecord rec = new CheckinRecord();
        rec.setUserId(userId);
        rec.setCheckinDate(today);
        rec.setPoints(points);
        rec.setStreak(streak);
        rec.setCreateTime(LocalDateTime.now());
        try {
            checkinMapper.insert(rec);
        } catch (DuplicateKeyException e) {
            // 并发重复签到：唯一键兜底，按"今日已签"幂等处理
            CheckinRecord exists = findCheckin(userId, today);
            if (exists == null) throw new BusinessException("签到失败，请重试");
            return checkinView(exists);
        }
        awardPoints(userId, points, "checkin:" + today, "每日签到奖励（连续 " + streak + " 天）");
        return checkinView(rec);
    }

    /**
     * 签到状态：今日是否已签 / 当前连签天数（已签取当日 streak，未签延续昨日记录，否则 0）/
     * 今日已签积分 / 本月签到日历（升序）。
     * @return {signedToday, streak, points, month:[{date, points}]}
     */
    public Map<String, Object> checkinStatus(Long userId) {
        if (userId == null) throw new BusinessException("请先登录");
        if (checkinMapper == null) throw new BusinessException("签到功能暂不可用");
        LocalDate today = LocalDate.now();
        CheckinRecord todayRec = findCheckin(userId, today);
        CheckinRecord yesterday = todayRec == null ? findCheckin(userId, today.minusDays(1)) : null;
        int streak;
        if (todayRec != null) {
            streak = todayRec.getStreak() == null ? 1 : todayRec.getStreak();
        } else {
            streak = yesterday == null || yesterday.getStreak() == null ? 0 : yesterday.getStreak();
        }
        List<CheckinRecord> month = checkinMapper.selectList(Wrappers.<CheckinRecord>lambdaQuery()
                .eq(CheckinRecord::getUserId, userId)
                .ge(CheckinRecord::getCheckinDate, today.withDayOfMonth(1))
                .le(CheckinRecord::getCheckinDate, today.withDayOfMonth(today.lengthOfMonth()))
                .orderByAsc(CheckinRecord::getCheckinDate));
        List<Map<String, Object>> monthView = month.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", r.getCheckinDate() == null ? null : r.getCheckinDate().toString());
            m.put("points", r.getPoints() == null ? 0 : r.getPoints());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signedToday", todayRec != null);
        out.put("streak", streak);
        out.put("points", todayRec == null || todayRec.getPoints() == null ? 0 : todayRec.getPoints());
        out.put("month", monthView);
        return out;
    }

    private CheckinRecord findCheckin(Long userId, LocalDate date) {
        return checkinMapper.selectOne(Wrappers.<CheckinRecord>lambdaQuery()
                .eq(CheckinRecord::getUserId, userId)
                .eq(CheckinRecord::getCheckinDate, date));
    }

    /** 签到返回视图（date 统一 yyyy-MM-dd，LocalDate.toString 同口径） */
    private Map<String, Object> checkinView(CheckinRecord rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", rec.getCheckinDate() == null ? null : rec.getCheckinDate().toString());
        m.put("points", rec.getPoints() == null ? 0 : rec.getPoints());
        m.put("streak", rec.getStreak() == null ? 1 : rec.getStreak());
        m.put("signedToday", true);
        return m;
    }

}
