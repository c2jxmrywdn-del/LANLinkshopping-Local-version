package com.lanlink.shopping.integration.event;

import org.springframework.context.ApplicationEvent;

/**
 * 商品生命周期事件（跨模块通信协议）。
 * 当商品发生上架/审核/改价/改规格/下架/删除等状态流转时发布。
 * 向量知识库、搜索索引等模块异步消费此事件，实现秒级增量同步与自动知识收录。
 */
public class ProductLifecycleEvent extends ApplicationEvent {

    public enum Action {
        APPROVED,         // 审核通过 -> 自动上架入库
        REJECTED,         // 审核驳回 -> 移出知识库
        ON_SHELF,         // 重新上架 -> 入库
        OFF_SHELF,        // 下架 -> 移出知识库
        CONTENT_UPDATED,  // 核心内容变更（标题/规格/图文）-> 重新送审，移出知识库
        PRICE_UPDATED,    // 价格/库存变动 -> 若在售则平滑刷新向量
        DELETED           // 逻辑删除 -> 移出知识库
    }

    private final Long prodId;
    private final Action action;

    public ProductLifecycleEvent(Object source, Long prodId, Action action) {
        super(source);
        this.prodId = prodId;
        this.action = action;
    }

    public Long getProdId() {
        return prodId;
    }

    public Action getAction() {
        return action;
    }

    @Override
    public String toString() {
        return "ProductLifecycleEvent{" +
                "prodId=" + prodId +
                ", action=" + action +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
