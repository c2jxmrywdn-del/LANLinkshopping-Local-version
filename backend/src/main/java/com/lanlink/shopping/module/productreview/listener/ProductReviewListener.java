package com.lanlink.shopping.module.productreview.listener;

import com.lanlink.shopping.module.productreview.event.ProductReviewRequestedEvent;
import com.lanlink.shopping.module.productreview.service.ProductAutoReviewService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 商品自动审核触发器：仅在商品发布/内容变更事务成功提交后入队，
 * 由 ProductAutoReviewService 的有界线程池异步执行，避免 AI/视觉慢调用阻塞发布响应。
 */
@Component
public class ProductReviewListener {

    private final ProductAutoReviewService autoReviewService;

    public ProductReviewListener(ProductAutoReviewService autoReviewService) {
        this.autoReviewService = autoReviewService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReviewRequested(ProductReviewRequestedEvent event) {
        if (event == null || event.getProdId() == null) return;
        autoReviewService.submit(event.getProdId());
    }
}
