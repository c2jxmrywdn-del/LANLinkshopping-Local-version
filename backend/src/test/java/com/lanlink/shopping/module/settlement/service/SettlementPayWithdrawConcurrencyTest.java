package com.lanlink.shopping.module.settlement.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.module.finance.entity.MerchantWithdrawal;
import com.lanlink.shopping.module.finance.mapper.FinanceLedgerMapper;
import com.lanlink.shopping.module.finance.mapper.MerchantWithdrawalMapper;
import com.lanlink.shopping.module.finance.service.MerchantFundLockService;
import com.lanlink.shopping.module.finance.service.WithdrawalService;
import com.lanlink.shopping.module.settlement.entity.MerchantSettlement;
import com.lanlink.shopping.module.settlement.mapper.MerchantEarningMapper;
import com.lanlink.shopping.module.settlement.mapper.MerchantSettlementItemMapper;
import com.lanlink.shopping.module.settlement.mapper.MerchantSettlementMapper;
import com.lanlink.shopping.service.MerchantService;
import com.lanlink.shopping.service.SequenceService;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-A 通道 B 并发双付回归（真实 MySQL，两线程两连接）：结算批次登记打款 vs 商户提现申请，
 * 同一份「可提余额」只能被一条出款通道占用。
 *
 * <p>测的是<strong>服务层 + 真实 InnoDB 锁语义</strong>：真实 Spring 事务代理（等价生产
 * {@code @Transactional}）+ 真实 MyBatis-Plus 会话 + 真实 JdbcTemplate + 真实
 * {@link MerchantFundLockService}（商户行 {@code FOR UPDATE}）；仅商户解析/取号/附件三个
 * 与余额判定无关的外围协作用 mock。每次运行建独立一次性库（用后即删），不触碰开发库。
 *
 * <p>用例：T-B1 确定性锁观测（pay 必须阻塞在同商户 {@code t_merchant} 行锁上）；
 * T-B2 端到端竞态（恰好一成一 400）；T-B3 双向顺序交错 + 守卫失败回滚可恢复。
 * CI 无 MySQL 时 {@link Assumptions} 跳过而非失败（真机验收须本地执行）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementPayWithdrawConcurrencyTest {

    private static final String HOST = envOrProp("SPRING_DATASOURCE_HOST", "spring.datasource.host", "localhost");
    private static final String PORT = envOrProp("SPRING_DATASOURCE_PORT", "spring.datasource.port", "3306");
    private static final String USER = envOrProp("SPRING_DATASOURCE_USERNAME", "spring.datasource.username", "root");
    private static final String PASSWORD = envOrProp("SPRING_DATASOURCE_PASSWORD", "spring.datasource.password", "");

    private static String envOrProp(String envKey, String propKey, String def) {
        String v = System.getenv(envKey);
        if (v == null || v.isBlank()) {
            v = System.getProperty(propKey);
        }
        return v == null || v.isBlank() ? def : v;
    }

    private static final String SCHEMA = "stl_it_" + Long.toHexString(System.nanoTime());
    private static final long RUN_STAMP = System.currentTimeMillis();
    private static final BigDecimal BATCH_NET = new BigDecimal("800.00");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger WITHDRAW_NO_SEQ = new AtomicInteger();

    private SimpleDriverDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setup() throws Exception {
        // 无 MySQL（如 CI 未起库）时跳过而不是失败；本机 dev 库常驻时真实执行
        try (Connection probe = openServerConnectionOrSkip()) {
            try (Statement st = probe.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@transaction_isolation")) {
                rs.next();
                // 互斥+当前读的正确性依赖 InnoDB REPEATABLE READ / READ COMMITTED 快照；异常隔离级别跳过
                Assumptions.assumeTrue("REPEATABLE-READ".equalsIgnoreCase(rs.getString(1)),
                        "需要 REPEATABLE-READ 隔离级别（生产默认），当前：" + rs.getString(1));
            }
            execute(probe, "CREATE DATABASE IF NOT EXISTS " + SCHEMA
                    + " DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        dataSource = new SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(),
                "jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                        + "&useSSL=false&allowPublicKeyRetrieval=true",
                USER, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        createTables();
    }

    @AfterAll
    void dropSchema() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(serverUrl(), USER, PASSWORD)) {
            execute(conn, "DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    // ==================== T-B1：确定性锁观测 ====================

    /**
     * 改前必失败：pay 不取商户行锁时会立即返回成功。
     * 修复后：另一会话持有同商户 {@code t_merchant} 行锁期间，pay 必须阻塞；提交后成功且收益行全 PAID。
     */
    @Test
    void pay_blocks_on_merchant_row_lock() throws Exception {
        Seed seed = seedMerchantWithBatch("b1");
        Services svc = buildServices(seed.merId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection lockConn = dataSource.getConnection()) {
            lockConn.setAutoCommit(false);
            try (Statement st = lockConn.createStatement()) {
                st.execute("SET SESSION innodb_lock_wait_timeout = 10");
            }
            try (PreparedStatement ps = lockConn.prepareStatement(
                    "SELECT mer_id FROM t_merchant WHERE mer_id = ? FOR UPDATE")) {
                ps.setLong(1, seed.merId());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "锁观测连接必须能锁到商户行");
                }
            }
            Future<Object> f = pool.submit(() -> callPay(svc, seed.settleId(), "V-B1"));

            assertThrows(TimeoutException.class, () -> f.get(500, TimeUnit.MILLISECONDS),
                    "pay 必须被同商户 t_merchant 行锁阻塞（未取锁的旧实现会立即返回）");

            lockConn.commit();
            Object out = f.get(10, TimeUnit.SECONDS);
            assertTrue(out instanceof MerchantSettlement, "锁释放后 pay 应成功，实际=" + out);
        } finally {
            pool.shutdownNow();
        }
        Integer notPaid = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_merchant_earning WHERE mer_id=? AND status<>'PAID'",
                Integer.class, seed.merId());
        assertEquals(0, notPaid, "批次 PAID 后收益行必须全部 PAID");
    }

    // ==================== T-B2：端到端竞态 ====================

    /** pay 与提现申请同发：锁串行化后恰好一成一 400，且资金不变量成立（不存在两条通道同时成功）。 */
    @Test
    void concurrent_pay_and_withdraw_only_one_wins() throws Exception {
        for (int round = 0; round < 3; round++) {
            String tag = "b2-" + round;
            Seed seed = seedMerchantWithBatch(tag);
            Services svc = buildServices(seed.merId());

            CountDownLatch startGate = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Object> outcomes = new ArrayList<>();
            try {
                Future<Object> payF = pool.submit(() -> {
                    startGate.await();
                    return callPay(svc, seed.settleId(), "V-" + tag);
                });
                Future<Object> wdF = pool.submit(() -> {
                    startGate.await();
                    return callWithdraw(svc, BATCH_NET);
                });
                startGate.countDown();
                outcomes.add(payF.get(60, TimeUnit.SECONDS));
                outcomes.add(wdF.get(60, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }

            assertTrue(outcomes.stream().allMatch(o -> o instanceof MerchantSettlement
                            || o instanceof MerchantWithdrawal || o instanceof BusinessException),
                    "不允许出现非预期异常（500/SQL 错误）: " + outcomes);
            long wins = outcomes.stream()
                    .filter(o -> o instanceof MerchantSettlement || o instanceof MerchantWithdrawal)
                    .count();
            List<BusinessException> rejects = outcomes.stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .toList();
            assertEquals(1, wins, "恰好一条出款通道成功，实际=" + wins + "，结果=" + describe(outcomes));
            assertEquals(1, rejects.size(), "恰好一条被拒，结果=" + describe(outcomes));
            assertEquals(400, rejects.get(0).getCode(), "拒绝必须是业务 400: " + rejects.get(0).getMessage());

            // 资金不变量：要么批次 PAID 且收益全部离开 SETTLED 口径、无提现单；
            // 要么提现冻结 800 且批次仍 APPROVED（pay 回滚）。
            String batchStatus = jdbc.queryForObject(
                    "SELECT status FROM t_merchant_settlement WHERE settle_id=?", String.class, seed.settleId());
            BigDecimal settledSum = settledSum(seed);
            BigDecimal wdSum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount),0) FROM t_merchant_withdrawal WHERE mer_id=? AND status IN (0,1)",
                    BigDecimal.class, seed.merId());
            if ("PAID".equals(batchStatus)) {
                assertEquals(0, settledSum.compareTo(BigDecimal.ZERO.setScale(2)),
                        "pay 胜出：收益不得再留在可提口径");
                assertEquals(0, wdSum.compareTo(BigDecimal.ZERO.setScale(2)),
                        "pay 胜出：不得存在在途/已打款提现");
            } else {
                assertEquals("APPROVED", batchStatus, "apply 胜出：批次必须回滚回 APPROVED");
                assertEquals(0, settledSum.compareTo(BATCH_NET), "apply 胜出：收益仍全部可提");
                assertEquals(0, wdSum.compareTo(BATCH_NET), "apply 胜出：冻结恰为本批净额");
            }
        }
    }

    // ==================== T-B3：双向顺序交错 ====================

    /** 先提现（冻结 800）→ pay 必须 400 且事务回滚（批次仍 APPROVED）；驳回在途提现后可重试成功。 */
    @Test
    void withdrawal_first_then_pay_rejected_then_recoverable() throws Exception {
        Seed seed = seedMerchantWithBatch("b3a");
        Services svc = buildServices(seed.merId());

        MerchantWithdrawal w = svc.withdrawal().apply(1L, BATCH_NET, "并发测试公司",
                "6222000011112222", "工商银行深圳分行", null);

        Object rejected = callPay(svc, seed.settleId(), "V-B3A");
        assertTrue(rejected instanceof BusinessException,
                "提现冻结后 pay 必须被余额守卫拒绝，实际=" + rejected);
        BusinessException be = (BusinessException) rejected;
        assertEquals(400, be.getCode());
        assertTrue(be.getMessage().contains("提现") || be.getMessage().contains("占用"),
                "守卫文案须提示提现占用: " + be.getMessage());
        assertTrue(be.getMessage().contains("驳回") || be.getMessage().contains("撤回"),
                "守卫文案须给出可行动修复路径: " + be.getMessage());

        // 守卫抛异常 → @Transactional 回滚：批次状态与收益行都不能被部分改写
        assertEquals("APPROVED", batchStatus(seed), "守卫失败必须回滚批次 PAID 更新");
        assertEquals(0, settledSum(seed).compareTo(BATCH_NET), "守卫失败必须回滚收益行 PAID");

        // 运营驳回在途提现后重试 → 可恢复
        svc.withdrawal().reject(999L, w.getId(), "并发验收驳回");
        Object retried = callPay(svc, seed.settleId(), "V-B3A-2");
        assertTrue(retried instanceof MerchantSettlement, "驳回提现后重试 pay 应成功，实际=" + retried);
        assertEquals("PAID", batchStatus(seed));
        assertEquals(0, settledSum(seed).compareTo(BigDecimal.ZERO.setScale(2)));
    }

    /** 先 pay（收益转 PAID）→ 提现申请必须 400「可提余额不足」，不落单。 */
    @Test
    void pay_first_then_withdraw_rejected() throws Exception {
        Seed seed = seedMerchantWithBatch("b3b");
        Services svc = buildServices(seed.merId());

        Object paid = callPay(svc, seed.settleId(), "V-B3B");
        assertTrue(paid instanceof MerchantSettlement, "pay 应成功，实际=" + paid);
        assertEquals(0, settledSum(seed).compareTo(BigDecimal.ZERO.setScale(2)),
                "pay 后收益离开可提口径");

        Object rejected = callWithdraw(svc, BATCH_NET);
        assertTrue(rejected instanceof BusinessException, "pay 后提现必须被拒，实际=" + rejected);
        BusinessException be = (BusinessException) rejected;
        assertEquals(400, be.getCode());
        assertTrue(be.getMessage().contains("可提余额不足"), "实际=" + be.getMessage());
        Integer wdRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_merchant_withdrawal WHERE mer_id=?", Integer.class, seed.merId());
        assertEquals(0, wdRows, "被拒提现不得落单/冻结");
    }

    // ==================== 装配与数据 ====================

    private record Seed(long merId, long settleId) {
    }

    private record Services(WithdrawalService withdrawal, SettlementBatchService batch) {
    }

    private Object callPay(Services svc, long settleId, String voucher) {
        try {
            return svc.batch().pay(settleId, voucher);
        } catch (BusinessException e) {
            return e;
        }
    }

    private Object callWithdraw(Services svc, BigDecimal amount) {
        try {
            return svc.withdrawal().apply(1L, amount, "并发测试公司", "6222000011112222",
                    "工商银行深圳分行", null);
        } catch (BusinessException e) {
            return e;
        }
    }

    private String batchStatus(Seed seed) {
        return jdbc.queryForObject("SELECT status FROM t_merchant_settlement WHERE settle_id=?",
                String.class, seed.settleId());
    }

    private BigDecimal settledSum(Seed seed) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(net_amount),0) FROM t_merchant_earning WHERE mer_id=? "
                        + "AND status IN ('SETTLED','REVERSED')", BigDecimal.class, seed.merId());
    }

    /** 真实事务语义：TransactionInterceptor 按 @Transactional 开启事务，JdbcTemplate 与 MyBatis 共享同一线程连接。 */
    private Services buildServices(long merId) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("settlement-it",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(MerchantWithdrawalMapper.class);
        configuration.addMapper(FinanceLedgerMapper.class);
        configuration.addMapper(MerchantEarningMapper.class);
        configuration.addMapper(MerchantSettlementMapper.class);
        configuration.addMapper(MerchantSettlementItemMapper.class);
        SqlSessionTemplate sqlSession = new SqlSessionTemplate(
                new MybatisSqlSessionFactoryBuilder().build(configuration));

        MerchantWithdrawalMapper wdMapper = sqlSession.getMapper(MerchantWithdrawalMapper.class);
        MerchantFundLockService fundLock = new MerchantFundLockService(jdbc, wdMapper);

        MerchantService merchantService = mock(MerchantService.class);
        Merchant m = new Merchant();
        m.setMerId(merId);
        m.setUserId(1L);
        when(merchantService.getByUser(anyLong())).thenReturn(m);
        SequenceService sequenceService = mock(SequenceService.class);
        when(sequenceService.nextNo(anyString(), eq(4)))
                .thenAnswer(inv -> String.format("%04d", WITHDRAW_NO_SEQ.incrementAndGet()));

        WithdrawalService withdrawalTarget = new WithdrawalService(wdMapper,
                sqlSession.getMapper(FinanceLedgerMapper.class), merchantService, sequenceService,
                mock(com.lanlink.shopping.module.attachment.service.AttachmentService.class), fundLock);
        SettlementBatchService batchTarget = new SettlementBatchService(
                sqlSession.getMapper(MerchantEarningMapper.class),
                sqlSession.getMapper(MerchantSettlementMapper.class),
                sqlSession.getMapper(MerchantSettlementItemMapper.class),
                mock(MerchantMapper.class), fundLock);

        return new Services(transactional(withdrawalTarget, WithdrawalService.class),
                transactional(batchTarget, SettlementBatchService.class));
    }

    private <T> T transactional(T target, Class<T> type) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    /** 建一个商户 + 4 条 SETTLED EARN（合计 800）+ APPROVED 批次（total_net=800, item_count=4）+ 4 条明细。 */
    private Seed seedMerchantWithBatch(String tag) {
        int seq = SEQ.incrementAndGet();
        org.springframework.jdbc.support.GeneratedKeyHolder merchantKey =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO t_merchant (ent_id, user_id, review_status) VALUES (990001, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, -(RUN_STAMP + seq));
            return ps;
        }, merchantKey);
        long merId = merchantKey.getKey().longValue();

        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO t_merchant_earning (mer_id, order_no, item_id, prod_id, type, "
                            + "refund_seq, gross_amount, commission_rate, commission_amount, net_amount, "
                            + "status, order_subtotal, order_total) VALUES (?,?,?,?,?,0,?,?,?,?,?,?,?)",
                    merId, "STL" + RUN_STAMP + "-" + tag + "-" + i, i, 1L, "EARN",
                    new BigDecimal("200.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("200.00"), "SETTLED",
                    new BigDecimal("200.00"), new BigDecimal("200.00"));
        }
        List<Long> earningIds = jdbc.queryForList(
                "SELECT earning_id FROM t_merchant_earning WHERE mer_id=? ORDER BY earning_id",
                Long.class, merId);

        org.springframework.jdbc.support.GeneratedKeyHolder batchKey =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        String settleNo = "ITSTL" + RUN_STAMP + "-" + tag;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO t_merchant_settlement (settle_no, mer_id, total_net, commission_total, "
                            + "item_count, status) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settleNo);
            ps.setLong(2, merId);
            ps.setBigDecimal(3, BATCH_NET);
            ps.setBigDecimal(4, BigDecimal.ZERO);
            ps.setInt(5, earningIds.size());
            ps.setString(6, "APPROVED");
            return ps;
        }, batchKey);
        long settleId = batchKey.getKey().longValue();

        for (int i = 0; i < earningIds.size(); i++) {
            jdbc.update("INSERT INTO t_merchant_settlement_item "
                            + "(settle_id, earning_id, order_no, item_id, type, net_amount) VALUES (?,?,?,?,?,?)",
                    settleId, earningIds.get(i), "STL" + RUN_STAMP + "-" + tag + "-" + (i + 1),
                    (long) (i + 1), "EARN", new BigDecimal("200.00"));
        }
        return new Seed(merId, settleId);
    }

    private void createTables() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS t_merchant ("
                    + " mer_id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + " ent_id BIGINT NOT NULL, user_id BIGINT NOT NULL,"
                    + " review_status TINYINT DEFAULT 0, create_time DATETIME DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS t_merchant_earning ("
                    + " earning_id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                    + " mer_id BIGINT NOT NULL, order_no VARCHAR(40) NOT NULL,"
                    + " item_id BIGINT NOT NULL, prod_id BIGINT NOT NULL,"
                    + " type VARCHAR(8) NOT NULL, refund_seq INT NOT NULL DEFAULT 0,"
                    + " gross_amount DECIMAL(14,2) NOT NULL, commission_rate DECIMAL(5,2) NOT NULL,"
                    + " commission_amount DECIMAL(14,2) NOT NULL, net_amount DECIMAL(14,2) NOT NULL,"
                    + " status VARCHAR(12) NOT NULL, order_subtotal DECIMAL(14,2) NOT NULL,"
                    + " order_total DECIMAL(14,2) NOT NULL,"
                    + " UNIQUE KEY uk_earning_line (order_no, item_id, type, refund_seq),"
                    + " KEY idx_earning_mer_status (mer_id, status)) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS t_merchant_settlement ("
                    + " settle_id BIGINT PRIMARY KEY AUTO_INCREMENT, settle_no VARCHAR(40) NOT NULL,"
                    + " mer_id BIGINT NOT NULL, period_start DATETIME NULL, period_end DATETIME NULL,"
                    + " total_net DECIMAL(14,2) NOT NULL,"
                    + " commission_total DECIMAL(14,2) NOT NULL, item_count INT NOT NULL,"
                    + " status VARCHAR(12) NOT NULL, pay_voucher VARCHAR(128) NULL,"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + " UNIQUE KEY uk_settle_no (settle_no), KEY idx_settle_mer (mer_id, status)) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS t_merchant_settlement_item ("
                    + " id BIGINT PRIMARY KEY AUTO_INCREMENT, settle_id BIGINT NOT NULL,"
                    + " earning_id BIGINT NOT NULL, order_no VARCHAR(40) NOT NULL, item_id BIGINT NOT NULL,"
                    + " type VARCHAR(8) NOT NULL, net_amount DECIMAL(14,2) NOT NULL,"
                    + " UNIQUE KEY uk_settle_item (settle_id, earning_id),"
                    + " KEY idx_sitem_settle (settle_id), KEY idx_sitem_earning (earning_id)) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS t_merchant_withdrawal ("
                    + " id BIGINT PRIMARY KEY AUTO_INCREMENT, mer_id BIGINT NOT NULL,"
                    + " withdraw_no VARCHAR(40) NOT NULL, amount DECIMAL(14,2) NOT NULL,"
                    + " balance_snapshot DECIMAL(14,2) NOT NULL, bank_account_name VARCHAR(64) NOT NULL,"
                    + " bank_account_no_masked VARCHAR(64) NOT NULL, bank_name VARCHAR(100) NOT NULL,"
                    + " status TINYINT NOT NULL DEFAULT 0, apply_note VARCHAR(255) NULL,"
                    + " reject_reason VARCHAR(255) NULL,"
                    + " voucher_url VARCHAR(255) NULL, reviewed_by BIGINT NULL, reviewed_at DATETIME NULL,"
                    + " create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + " update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + " UNIQUE KEY uk_withdraw_no (withdraw_no),"
                    + " KEY idx_wd_mer_status (mer_id, status)) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS t_finance_ledger ("
                    + " id BIGINT PRIMARY KEY AUTO_INCREMENT, mer_id BIGINT NOT NULL,"
                    + " biz_type VARCHAR(32) NOT NULL, biz_no VARCHAR(40) NOT NULL,"
                    + " amount DECIMAL(14,2) NOT NULL, balance_after DECIMAL(14,2) NOT NULL,"
                    + " remark VARCHAR(255) NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + " KEY idx_ledger_mer (mer_id, id)) ENGINE=InnoDB");
        }
    }

    /** 连不上本机 MySQL（未起库/账号密码不符）视为环境前提不满足：跳过而不是报错。 */
    private static Connection openServerConnectionOrSkip() throws SQLException {
        try {
            return DriverManager.getConnection(serverUrl(), USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.abort("本机 MySQL 不可用（" + HOST + ":" + PORT + "，user=" + USER
                    + "）：" + e.getMessage()
                    + "；起库或注入 SPRING_DATASOURCE_* 环境变量后本并发回归真实执行");
            return null; // abort() 必抛 TestAbortedException，此行仅为通过编译
        }
    }

    private static String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    }

    private static void execute(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static String describe(List<Object> outcomes) {
        List<String> parts = new ArrayList<>();
        for (Object o : outcomes) {
            if (o instanceof MerchantSettlement s) {
                parts.add("结算PAID(settleId=" + s.getSettleId() + ")");
            } else if (o instanceof MerchantWithdrawal w) {
                parts.add("提现成功(no=" + w.getWithdrawNo() + ", amount=" + w.getAmount() + ")");
            } else if (o instanceof BusinessException e) {
                parts.add(e.getCode() + ":" + e.getMessage());
            } else {
                parts.add(String.valueOf(o));
            }
        }
        return parts.toString();
    }
}
