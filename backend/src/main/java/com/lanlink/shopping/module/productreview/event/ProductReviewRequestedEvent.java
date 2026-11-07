package com.lanlink.shopping.module.productreview.event;

import org.springframework.context.ApplicationEvent;

/**
 * 商品提交自动审核事件（发布/内容修改事务提交后异步消费）。
 * 与商品生命周期事件解耦：知识库同步只关心状态流转，自动审核只关心“新送审”。
 */
public class ProductReviewRequestedEvent extends ApplicationEvent {

    public enum Reason { NEW_PUBLISH, CONTENT_UPDATED, MANUAL_RERUN }

    private final Long prodId;
    private final Reason reason;

    public ProductReviewRequestedEvent(Object source, Long prodId, Reason reason) {
        super(source);
        this.prodId = prodId;
        this.reason = reason;
    }

    public Long getProdId() {
        return prodId;
    }

    public Reason getReason() {
        return reason;
    }
}
