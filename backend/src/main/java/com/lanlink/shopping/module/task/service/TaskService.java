package com.lanlink.shopping.module.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.module.task.entity.TaskRecord;
import com.lanlink.shopping.module.task.mapper.TaskRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务激励服务（F3 最小闭环）。
 *  - 首单任务：TaskOrderListener 监听 OrderPaidEvent（AFTER_COMMIT + REQUIRES_NEW）自动发放；
 *  - 分享任务：前端分享回执 POST /task/claim 手动领取，每日上限服务端按当日流水计数 + 唯一键兜底。
 * 奖励统一走 MembershipService.awardPoints（refNo 幂等 + uk_point_user_type_ref 唯一键兜底），
 * 重复事件不会重复计分；任务流水 uk_task_user_key_ref(user_id, task_key, ref_no) 防重复落账。
 * 任务不是促销：不进 t_campaign 七类、不进 isPromoType/matchTab（activity-plan.md §4.2）。
 */
@Service
public class TaskService {

    private final TaskRecordMapper taskRecordMapper;
    private final MembershipService membershipService;

    public TaskService(TaskRecordMapper taskRecordMapper, MembershipService membershipService) {
        this.taskRecordMapper = taskRecordMapper;
        this.membershipService = membershipService;
    }

    // ==================== 首单任务（事件自动发放） ====================

    /**
     * 首单任务发放（TaskOrderListener 入口）：同一用户仅首笔已支付订单奖励一次。
     * 幂等三层：
     *  1. 既有流水守卫——(user_id, first_order) 已有记录直接返回；
     *  2. 落库唯一键——uk_task_user_key_ref 兜底重复事件/并发（DuplicateKey 幂等返回）；
     *  3. 积分幂等——awardPoints 固定引用号 "task:first_order:{userId}"（与订单号无关），
     *     即使并发两笔首单事件各自通过守卫，积分也只在积分流水唯一键层面放行一次。
     * 记录与积分同事务：中途异常整体回滚，不出现「有流水无积分」半态。
     */
    @Transactional
    public void completeFirstOrder(Long userId, String orderNo) {
        if (userId == null || orderNo == null || orderNo.isBlank()) return;
        TaskCatalog.TaskDef def = TaskCatalog.byKey(TaskCatalog.TASK_FIRST_ORDER);
        Long done = taskRecordMapper.selectCount(Wrappers.<TaskRecord>lambdaQuery()
                .eq(TaskRecord::getUserId, userId)
                .eq(TaskRecord::getTaskKey, def.key()));
        if (done != null && done > 0) return; // 首单奖励已发放（幂等）
        insertAndAward(userId, def, orderNo, "task:first_order:" + userId, "首单任务奖励：" + def.title());
    }

    // ==================== 分享任务（手动回执领取） ====================

    /**
     * 任务回执领取（POST /task/claim，P1 仅分享任务可手动领取）。
     * @param taskKey 任务标识（first_order 由系统自动发放，手动领取直接拒绝）
     * @param refNo   分享回执标识（如商品ID）；服务端拼接当日 yyyyMMdd 形成幂等引用，不信任前端日期
     * @return {taskKey, refNo, points, awarded}（awarded=false = 今日该回执已领过，幂等返回）
     */
    @Transactional
    public Map<String, Object> claim(Long userId, String taskKey, String refNo) {
        if (userId == null) throw new BusinessException("请先登录");
        TaskCatalog.TaskDef def = TaskCatalog.byKey(taskKey);
        if (def == null) throw new BusinessException("任务不存在");
        if (TaskCatalog.TASK_FIRST_ORDER.equals(def.key())) {
            throw new BusinessException("首单任务由系统自动发放，无需手动领取");
        }
        if (refNo == null || refNo.isBlank()) throw new BusinessException("缺少分享回执标识");
        String raw = refNo.trim();
        if (raw.length() > 48) throw new BusinessException("分享回执标识过长");

        LocalDate today = LocalDate.now();
        // 幂等引用 = 回执标识:yyyyMMdd（与 t_task_record.ref_no 规划口径一致）：
        // 同一回执当日仅一次（唯一键兜底），跨日重分享可再计（受每日上限约束）
        String idempotentRef = raw + ":" + today.format(DateTimeFormatter.BASIC_ISO_DATE);
        LocalDateTime dayStart = today.atStartOfDay();
        Long todayCount = taskRecordMapper.selectCount(Wrappers.<TaskRecord>lambdaQuery()
                .eq(TaskRecord::getUserId, userId)
                .eq(TaskRecord::getTaskKey, def.key())
                .ge(TaskRecord::getCreateTime, dayStart));
        if (todayCount != null && todayCount >= def.dailyLimit()) {
            throw new BusinessException("今日分享奖励已达上限（" + def.dailyLimit() + " 次/日）");
        }
        boolean awarded = insertAndAward(userId, def, idempotentRef,
                "task:share_product:" + idempotentRef, "分享任务奖励：" + def.title());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskKey", def.key());
        out.put("refNo", idempotentRef);
        out.put("points", def.points());
        out.put("awarded", awarded);
        return out;
    }

    /** 落任务流水（唯一键兜底）→ 发积分；重复落库（DuplicateKey）幂等返回 false，不重复发分 */
    private boolean insertAndAward(Long userId, TaskCatalog.TaskDef def, String refNo, String awardRefNo, String remark) {
        TaskRecord rec = new TaskRecord();
        rec.setUserId(userId);
        rec.setTaskKey(def.key());
        rec.setRefNo(refNo);
        rec.setPoints(def.points());
        rec.setGrowth(def.growth());
        rec.setCreateTime(LocalDateTime.now());
        try {
            taskRecordMapper.insert(rec);
        } catch (DuplicateKeyException e) {
            // 并发/重复事件：uk_task_user_key_ref 已有同引用记录 → 幂等返回（不重复发分）
            return false;
        }
        membershipService.awardPoints(userId, def.points(), awardRefNo, remark);
        return true;
    }

    // ==================== 任务中心（列表 + 今日进度） ====================

    /**
     * 我的任务列表（GET /task/my）：目录定义 + 总进度 + 今日进度 + 完成态。
     *  - first_order：status=completed 当 totalDone≥1（一次性）；
     *  - share_product：status=completed 当今日已达每日上限（可重复任务，跨日重置）。
     */
    public List<Map<String, Object>> myTasks(Long userId) {
        if (userId == null) throw new BusinessException("请先登录");
        List<TaskRecord> mine = taskRecordMapper.selectList(Wrappers.<TaskRecord>lambdaQuery()
                .eq(TaskRecord::getUserId, userId)
                .orderByDesc(TaskRecord::getId));
        Map<String, List<TaskRecord>> byTask = mine.stream()
                .collect(Collectors.groupingBy(TaskRecord::getTaskKey));
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskCatalog.TaskDef def : TaskCatalog.all()) {
            List<TaskRecord> records = byTask.getOrDefault(def.key(), List.of());
            long todayDone = records.stream()
                    .filter(r -> r.getCreateTime() != null && !r.getCreateTime().isBefore(dayStart))
                    .count();
            boolean completed = TaskCatalog.TASK_FIRST_ORDER.equals(def.key())
                    ? !records.isEmpty()
                    : todayDone >= def.dailyLimit();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskKey", def.key());
            m.put("title", def.title());
            m.put("description", def.description());
            m.put("rewardPoints", def.points());
            m.put("rewardGrowth", def.growth());
            m.put("dailyLimit", def.dailyLimit());
            m.put("totalDone", records.size());
            m.put("todayDone", (int) todayDone);
            m.put("remainingToday", (int) Math.max(0, def.dailyLimit() - todayDone));
            m.put("status", completed ? "completed" : "open");
            m.put("lastDoneTime", records.isEmpty() ? null : records.get(0).getCreateTime());
            out.add(m);
        }
        return out;
    }
}
