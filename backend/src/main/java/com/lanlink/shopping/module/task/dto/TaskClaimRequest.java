package com.lanlink.shopping.module.task.dto;

import lombok.Data;

/**
 * 任务回执领取请求（F3，POST /task/claim）。
 * refNo 为前端分享回执标识（如商品ID）；服务端拼接当日 yyyyMMdd 形成幂等引用，不信任前端日期。
 */
@Data
public class TaskClaimRequest {
    /** 任务标识（P1 仅 share_product 可手动领取；first_order 由订单支付事件自动发放） */
    private String taskKey;
    /** 分享回执标识 */
    private String refNo;
}
