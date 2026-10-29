package com.lanlink.shopping.module.aikb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 应用就绪后自动灌装本地知识库（独立线程，不阻塞启动；
 * 嵌入走降级链路，ollama 不在线也能完成哈希嵌入灌库）。
 */
@Component
public class KnowledgeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrap.class);

    private final KnowledgeIngestService ingestService;

    public KnowledgeBootstrap(KnowledgeIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread t = new Thread(() -> {
            try {
                Map<String, Integer> result = ingestService.ingestAll();
                log.info("[aikb] bootstrap ingest done: {}", result);
            } catch (Exception e) {
                log.warn("[aikb] bootstrap ingest failed: {}", e.getMessage());
            }
        }, "aikb-bootstrap");
        t.setDaemon(true);
        t.start();
    }
}
