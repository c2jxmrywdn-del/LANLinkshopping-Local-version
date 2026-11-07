package com.lanlink.shopping.module.aikb;

/**
 * 知识灌库暂时不可用（全量重建占用、同商品锁竞争等）。
 * 这不是业务失败，Outbox 只应短暂回队，不应消耗 retry_count 或转死信。
 */
public class KbIngestBusyException extends RuntimeException {

    public KbIngestBusyException(String message) {
        super(message);
    }
}
