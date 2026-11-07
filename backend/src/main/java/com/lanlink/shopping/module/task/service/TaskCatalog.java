package com.lanlink.shopping.module.task.service;

import java.util.List;

/**
 * 任务定义目录（F3 P1 代码内常量，不建定义表；P2 运营化再建 t_task_definition）。
 * 任务只做两个（activity-plan.md §P1.4，同调研 topPick 2 口径）：
 *  - first_order 首单有礼：OrderPaidEvent 自动发放（TaskOrderListener），一次性；
 *  - share_product 分享有礼：前端分享回执 + POST /task/claim，每日限领。
 * 奖励数值为 openQuestion 5 建议档（首单 +100 积分/+100 成长值、分享 +5 积分、每日 3 次），
 * 运营拍板后在此锁定，不改链路。
 * 「浏览 N 秒」「带图评价」明确不做（§2.3 落选理由）。
 */
public final class TaskCatalog {

    /** 任务标识：首单有礼（订单支付事件自动发放，无手动入口） */
    public static final String TASK_FIRST_ORDER = "first_order";
    /** 任务标识：分享有礼（POST /task/claim 手动回执） */
    public static final String TASK_SHARE_PRODUCT = "share_product";

    /** 首单有礼：一次性任务（每日上限语义 = 仅 1 次/终身） */
    public static final TaskDef FIRST_ORDER =
            new TaskDef(TASK_FIRST_ORDER, "首单有礼", "完成首笔已支付订单，奖励积分与成长值", 100, 100, 1);

    /** 分享有礼：每日前 3 次有效，同一回执当日仅一次（唯一键兜底） */
    public static final TaskDef SHARE_PRODUCT =
            new TaskDef(TASK_SHARE_PRODUCT, "分享有礼", "分享商品给好友，每日前 3 次有奖", 5, 0, 3);

    private TaskCatalog() {
    }

    /** 单条任务定义：key / 标题 / 描述 / 奖励积分 / 奖励成长值 / 每日上限（1 = 仅一次） */
    public record TaskDef(String key, String title, String description, int points, int growth, int dailyLimit) {
    }

    /** 全部任务定义（任务中心展示顺序） */
    public static List<TaskDef> all() {
        return List.of(FIRST_ORDER, SHARE_PRODUCT);
    }

    /** 按标识取任务定义（未知道具返回 null，调用方按非法任务拒绝） */
    public static TaskDef byKey(String key) {
        if (key == null) return null;
        return switch (key) {
            case TASK_FIRST_ORDER -> FIRST_ORDER;
            case TASK_SHARE_PRODUCT -> SHARE_PRODUCT;
            default -> null;
        };
    }
}
