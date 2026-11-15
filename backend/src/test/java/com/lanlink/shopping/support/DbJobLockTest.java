package com.lanlink.shopping.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 跨实例任务互斥（R04）。
 *
 * <p>缺陷形态：RFM / 推荐共现 / 热度榜 / 商品向量索引都是 {@code clear() → rebuild()}
 * 的非幂等序列，多实例同时进入会互相清掉半成品，且没有任何报错。
 *
 * <p>这里锁定三条契约：拿不到锁时任务体不执行；拿到锁时执行并释放；任务抛异常也要释放锁。
 */
class DbJobLockTest {

    private JdbcTemplate jdbc;
    private DbJobLock lock;
    private Connection con;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        lock = new DbJobLock(jdbc);
        con = mock(Connection.class);
        // JdbcTemplate.execute(ConnectionCallback) 直接把 mock 连接交给回调
        when(jdbc.execute(any(ConnectionCallback.class)))
                .thenAnswer(inv -> ((ConnectionCallback<?>) inv.getArgument(0)).doInConnection(con));
    }

    /** 让 GET_LOCK / RELEASE_LOCK 返回指定值 */
    private void stubLockResult(int getLockValue) throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(getLockValue);
    }

    @Test
    @DisplayName("拿不到锁时跳过本轮，任务体不得执行")
    void skipsWhenLockHeldByAnotherInstance() throws Exception {
        stubLockResult(0);
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean result = lock.runExclusive("lanlink:job:rfm-rebuild", () -> ran.set(true));

        assertFalse(result, "未抢到锁应返回 false");
        assertFalse(ran.get(), "未抢到锁时任务体绝不能执行");
    }

    @Test
    @DisplayName("抢到锁时执行任务并返回 true")
    void runsWhenLockAcquired() throws Exception {
        stubLockResult(1);
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean result = lock.runExclusive("lanlink:job:rfm-rebuild", () -> ran.set(true));

        assertTrue(result);
        assertTrue(ran.get());
        // 释放锁必须被调用（连接会回池复用，不释放会一直被占）
        verify(con, atLeast(2)).prepareStatement(anyString());
    }

    @Test
    @DisplayName("任务抛异常时仍要释放锁，且异常向上抛")
    void releasesLockWhenTaskThrows() throws Exception {
        stubLockResult(1);

        assertThrows(IllegalStateException.class,
                () -> lock.runExclusive("lanlink:job:x", () -> {
                    throw new IllegalStateException("boom");
                }));

        verify(con, atLeast(2)).prepareStatement(anyString());
    }
}
