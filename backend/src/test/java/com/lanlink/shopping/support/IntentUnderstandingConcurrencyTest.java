package com.lanlink.shopping.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentUnderstandingConcurrencyTest {
    @Test
    void analyzesConcurrentRequestsWithoutSharedStateCorruption() throws Exception {
        var service = new IntentUnderstandingService();
        var pool = Executors.newFixedThreadPool(8);
        var done = new CountDownLatch(100);
        var successes = new AtomicInteger();
        for (int i = 0; i < 100; i++) pool.submit(() -> {
            try {
                if (service.analyze("我想查订单", java.util.List.of()).data().containsKey("intent")) successes.incrementAndGet();
            } finally { done.countDown(); }
        });
        done.await();
        pool.shutdownNow();
        assertTrue(successes.get() == 100);
    }
}
