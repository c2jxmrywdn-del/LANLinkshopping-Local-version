package com.lanlink.shopping.module.attachment.service;

/**
 * 缩略图派生状态机（t_attachment.thumb_status）。
 *
 * <p>状态只描述"预览这件事的进度"，与材料本身的 status（PENDING/BOUND/EXPIRED，即绑定生命周期）
 * 正交：一份材料可以已绑定却没有预览，也可以预览就绪但后来被回收。
 *
 * <p>PENDING → PROCESSING → READY / SKIPPED；PROCESSING 前也可能因入队失败被补投扫描重新排队。
 * SKIPPED 是终态（非图片、魔数不符、解不出、像素超限），重试不会改变结果；
 * FAILED 只用于基础设施异常（存储/数据库），表示"再来一次可能就好了"。
 */
public final class AttachmentThumbStatus {

    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String READY = "READY";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    /** 尝试上限：一张坏图最多消耗 5 次消费，之后只能人工看死信，不能永远占着消费者 */
    public static final int MAX_ATTEMPTS = 5;

    private AttachmentThumbStatus() {
    }
}
