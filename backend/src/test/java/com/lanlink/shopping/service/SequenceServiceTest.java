package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.mapper.SequenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单号序列服务测试：验证并发取号不重号（替代 selectCount()+1 竞态取号的回归保障）。
 */
class SequenceServiceTest {

    private SequenceMapper mapper;
    private SequenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SequenceMapper.class);
        service = new SequenceService(mapper);
    }

    @Test
    void nextReturnsMonotonicSequence() {
        AtomicLong val = new AtomicLong();
        when(mapper.insertIgnore(anyString())).thenReturn(1);
        when(mapper.increment(anyString(), anyLong())).thenReturn(1);
        when(mapper.lastInsertId()).thenAnswer(inv -> val.incrementAndGet());

        assertEquals(1, service.next("waybill:20261016"));
        assertEquals(2, service.next("waybill:20261016"));
        assertEquals("0003", service.nextNo("waybill:20261016", 4));
    }

    @Test
    void nextFailsWhenSequenceRowMissing() {
        when(mapper.insertIgnore(anyString())).thenReturn(0);
        when(mapper.increment(anyString(), anyLong())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.next("waybill:20261016"));
    }

    @Test
    void concurrentNextNeverDuplicates() throws Exception {
        // 模拟数据库 UPDATE ... LAST_INSERT_ID(current_val+1) 的行锁原子自增语义：
        // 每次 increment 拿到互不重复的值，与真实 DB 一致（同一 val 内连接级 LAST_INSERT_ID()）
        ConcurrentHashMap<Long, Boolean> issued = new ConcurrentHashMap<>();
        AtomicLong val = new AtomicLong();
        when(mapper.insertIgnore(anyString())).thenReturn(1);
        when(mapper.increment(anyString(), anyLong())).thenAnswer(inv -> 1);
        when(mapper.lastInsertId()).thenAnswer(inv -> {
            long v = val.incrementAndGet();
            assertNull(issued.putIfAbsent(v, Boolean.TRUE), "并发取号出现重复序列: " + v);
            return v;
        });

        int threads = 32, perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Long> results = ConcurrentHashMap.newKeySet();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        results.add(service.next("waybill:20261016"));
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发测试超时");
        pool.shutdownNow();
        assertEquals(threads * perThread, results.size(), "并发取号存在重复或丢失");
    }
}
