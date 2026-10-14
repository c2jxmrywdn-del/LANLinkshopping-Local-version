package com.lanlink.shopping.module.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.activity.entity.Activity;
import com.lanlink.shopping.module.activity.entity.ActivityParticipant;
import com.lanlink.shopping.module.activity.mapper.ActivityMapper;
import com.lanlink.shopping.module.activity.mapper.ActivityParticipantMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 活动系统服务（营销中台·独立模块）。
 * 仅通过事件接收器（ActivityOrderListener）感知订单支付，不依赖任何外部业务模块。
 */
@Service
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final ActivityParticipantMapper participantMapper;
    private final MembershipService membershipService;

    public ActivityService(ActivityMapper activityMapper, ActivityParticipantMapper participantMapper,
                           MembershipService membershipService) {
        this.activityMapper = activityMapper;
        this.participantMapper = participantMapper;
        this.membershipService = membershipService;
    }

    /** 进行中的活动列表（含是否已参与标记） */
    public List<Map<String, Object>> ongoing(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Activity> acts = activityMapper.selectList(Wrappers.<Activity>lambdaQuery()
                .eq(Activity::getStatus, 1)
                .and(w -> w.isNull(Activity::getEndTime).or().ge(Activity::getEndTime, now))
                .orderByDesc(Activity::getActivityId));
        return acts.stream().map(a -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("activityId", a.getActivityId());
            m.put("title", a.getTitle());
            m.put("type", a.getType());
            m.put("description", a.getDescription());
            m.put("startTime", a.getStartTime());
            m.put("endTime", a.getEndTime());
            if (userId != null) {
                m.put("joined", participantMapper.selectCount(Wrappers.<ActivityParticipant>lambdaQuery()
                        .eq(ActivityParticipant::getActivityId, a.getActivityId())
                        .eq(ActivityParticipant::getUserId, userId)) > 0);
            }
            return m;
        }).toList();
    }

    /** 用户参与活动（幂等：同一活动不可重复参与） */
    @Transactional
    public void join(Long userId, Long activityId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null) throw new BusinessException("活动不存在");
        if (a.getStatus() == null || a.getStatus() != 1) throw new BusinessException("活动未在进行中");
        if (participantMapper.selectCount(Wrappers.<ActivityParticipant>lambdaQuery()
                .eq(ActivityParticipant::getActivityId, activityId)
                .eq(ActivityParticipant::getUserId, userId)) > 0) {
            throw new BusinessException("您已参与过该活动");
        }
        ActivityParticipant p = new ActivityParticipant();
        p.setActivityId(activityId);
        p.setUserId(userId);
        p.setBonusPoints(100); // 参与奖励 100 积分
        p.setJoinTime(LocalDateTime.now());
        participantMapper.insert(p);
        membershipService.awardPoints(userId, p.getBonusPoints(), "activity:" + activityId,
                "活动参与奖励：" + a.getTitle());
    }

    /** 我的参与记录 */
    public List<ActivityParticipant> my(Long userId) {
        return participantMapper.selectList(Wrappers.<ActivityParticipant>lambdaQuery()
                .eq(ActivityParticipant::getUserId, userId).orderByDesc(ActivityParticipant::getJoinTime));
    }

    /**
     * 消费有礼：订单支付事件触发，自动参与所有进行中的 purchase 活动（幂等）。
     * 供集成层 ActivityOrderListener 调用。
     */
    public void autoJoinPurchase(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Activity> purchaseActs = activityMapper.selectList(Wrappers.<Activity>lambdaQuery()
                .eq(Activity::getType, "purchase")
                .eq(Activity::getStatus, 1)
                .and(w -> w.isNull(Activity::getEndTime).or().ge(Activity::getEndTime, now)));
        for (Activity a : purchaseActs) {
            boolean joined = participantMapper.selectCount(Wrappers.<ActivityParticipant>lambdaQuery()
                    .eq(ActivityParticipant::getActivityId, a.getActivityId())
                    .eq(ActivityParticipant::getUserId, userId)) > 0;
            if (!joined) {
                ActivityParticipant p = new ActivityParticipant();
                p.setActivityId(a.getActivityId());
                p.setUserId(userId);
                p.setBonusPoints(100);
                p.setJoinTime(now);
                participantMapper.insert(p);
                membershipService.awardPoints(userId, p.getBonusPoints(), "activity:" + a.getActivityId(),
                        "消费活动奖励：" + a.getTitle());
            }
        }
    }
}
