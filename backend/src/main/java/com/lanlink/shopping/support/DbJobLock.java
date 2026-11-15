package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 跨实例定时任务互斥（基于 MySQL 命名锁，零新依赖）。
 *
 * <p>为什么需要：本项目有 31 个 {@code @Scheduled} 任务，其中「重建型」任务
 * （RFM 分层、推荐共现矩阵、热度榜、商品向量索引）是 {@code clear() → rebuild()}
 * 的非幂等序列。多实例部署时两个实例同时进入，一个实例的 clear 会清掉另一个实例
 * 刚写入的半成品，最终表里留下不完整数据，且不会有任何报错。
 *
 * <p>为什么用 MySQL 命名锁而不是 Redis 锁：
 * <ul>
 *   <li>重建型任务本来就要读 MySQL，DB 是必选依赖；引入 Redis 锁会多一个故障点；</li>
 *   <li>{@code GET_LOCK} 是会话级锁，连接断开自动释放，不会留下僵尸锁；</li>
 *   <li>不需要新增依赖（无 ShedLock / Redisson），改动面只有几行。</li>
 * </ul>
 *
 * <p><b>重要约束</b>：{@code GET_LOCK}/{@code RELEASE_LOCK} 必须跑在<b>同一条连接</b>上，
 * 而 {@link JdbcTemplate} 每次调用可能拿不同连接。因此这里把「取锁 + 执行 + 放锁」
 * 收在同一个 {@link JdbcTemplate#execute(ConnectionCallback)} 回调里，由 Spring 保证
 * 回调期间复用同一条连接。
 *
 * <p>拿不到锁时<b>直接跳过本轮</b>（不是排队等待）：定时任务下一轮会再来，
 * 排队只会让线程池被占满。
 */
@Component
public class DbJobLock {

    private static final Logger log = LoggerFactory.getLogger(DbJobLock.class);

    /** 锁等待秒数：0 = 拿不到立刻返回，不阻塞调度线程 */
    private static final int WAIT_SECONDS = 0;

    private final JdbcTemplate jdbc;

    public DbJobLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在跨实例互斥保护下执行任务。
     *
     * @param lockName 锁名（全局唯一，建议 {@code lanlink:job:<任务名>}）
     * @param task     任务体；拿不到锁时不会执行
     * @return true = 本次抢到锁并执行完；false = 其他实例正在执行，本轮跳过
     */
    public boolean runExclusive(String lockName, Runnable task) {
        Boolean ran = jdbc.execute((java.sql.Connection con) -> {
            boolean acquired = false;
            try (java.sql.PreparedStatement ps = con.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                ps.setString(1, lockName);
                ps.setInt(2, WAIT_SECONDS);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    acquired = rs.next() && rs.getInt(1) == 1;
                }
            }
            if (!acquired) {
                log.debug("[job-lock] {} 已被其他实例持有，跳过本轮", lockName);
                return Boolean.FALSE;
            }
            try {
                task.run();
                return Boolean.TRUE;
            } finally {
                // 必须显式释放：连接会回到池里被复用，不释放会让锁一直被这个会话占着
                try (java.sql.PreparedStatement ps = con.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    ps.setString(1, lockName);
                    ps.executeQuery().close();
                } catch (Exception releaseError) {
                    log.warn("[job-lock] {} 释放失败（连接关闭后会自动释放）: {}", lockName, releaseError.getMessage());
                }
            }
        });
        return Boolean.TRUE.equals(ran);
    }
}
