package com.lanlink.shopping.module.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.module.finance.entity.MerchantWithdrawal;
import com.lanlink.shopping.module.finance.mapper.FinanceLedgerMapper;
import com.lanlink.shopping.module.finance.mapper.MerchantWithdrawalMapper;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 防双花并发回归（真实 MySQL，两线程两连接）：同商户并发双申请提现，只允许一成一 400。
 *
 * <p>测的是<strong>服务层 + 真实 InnoDB 锁语义</strong>这一层：真实 Spring 事务代理
 * （TransactionInterceptor，等价生产 @Transactional 语义）+ 真实 MyBatis-Plus 会话 +
 * 真实 JdbcTemplate，仅商户解析/取号/附件三个外围协作者用 mock（与余额判定无关）。
 * 每次运行用一次性独立测试库（用后即删），不触碰开发库数据。
 *
 * <p>场景即复测实锤的双花现场：settled=800，两笔并发申请 500。修复前两笔都成功
 * （RR 读视图早建于对手提交，sumPaid/sumFrozen 快照读漏掉并发冻结）；修复后
 * 商户行锁 + 锁定读聚合串行化，恰好一成一 400「可提余额不足」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WithdrawalConcurrencyTest {

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

    private static final String SCHEMA = "wd_it_" + Long.toHexString(System.nanoTime());
    private static final long RUN_STAMP = System.currentTimeMillis();

    private SimpleDriverDataSource dataSource;
    private JdbcTemplate jdbc;
    private long merId;
    /** 本轮期望的可提余额（t_merchant_earning SETTLED 净额合计）。 */
    private static final BigDecimal SETTLED_TOTAL = new BigDecimal("800.00");
    private static final BigDecimal APPLY_AMOUNT = new BigDecimal("500.00");

    @BeforeAll
    void setup() throws Exception {
        // 无 MySQL（如 CI 未起库）时跳过而不是失败；本机 dev 库常驻时真实执行
        try (Connection probe = openServerConnectionOrSkip()) {
            try (Statement st = probe.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@transaction_isolation")) {
                rs.next();
                // 双花复现依赖 REPEATABLE READ 快照语义；库级隔离被改过则本测试的前提不成立，跳过
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
        seedMerchantWithSettledEarnings();
    }

    @AfterAll
    void dropSchema() throws Exception {
        if (dataSource == null) {
            return; // setup 未能建库（本机 MySQL 不可用被跳过等），没有一次性库可清理
        }
        try (Connection conn = DriverManager.getConnection(serverUrl(), USER, PASSWORD)) {
            execute(conn, "DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    /**
     * 同商户两线程并发 apply：恰好一成功一 400「可提余额不足」，冻结总额不超已结算净额，
     * 台账只落一行冻结。
     *
     * <p>锁步对齐（countDownLatch 起点之外的第二道栅栏）：先到者停在「快照读已建立」处，
     * 等后到者的 getByUser 读视图建好再放行——固定住双花现场的时间条件「后到者的
     * REPEATABLE READ 快照早于先到者提交」。不加这道栅栏时两线程会因连接建立时差
     * 自然串行，测不出双花（本测试在未修复代码上跑过，确实漏过去）。
     */
    @Test
    void concurrent_double_apply_only_one_wins() throws Exception {
        WithdrawalService svc = buildTransactionalService();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit((Callable<Object>) () -> {
                startGate.await();
                try {
                    return svc.apply(1L, APPLY_AMOUNT, "并发测试公司", "6222000011112222",
                            "工商银行深圳分行", null);
                } catch (BusinessException e) {
                    return e;
                }
            }));
        }
        startGate.countDown();
        List<Object> outcomes = new ArrayList<>();
        try {
            for (Future<Object> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long wins = outcomes.stream().filter(MerchantWithdrawal.class::isInstance).count();
        List<BusinessException> rejections = outcomes.stream()
                .filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .toList();
        assertTrue(outcomes.stream().allMatch(o -> o instanceof MerchantWithdrawal || o instanceof BusinessException),
                "不允许出现非预期异常（500/SQL 错误都会走到这里）: " + outcomes);
        assertEquals(1, wins, "恰好一成功，实际成功笔数=" + wins + "，结果=" + describe(outcomes));
        assertEquals(1, rejections.size(), "恰好一拒绝，实际拒绝笔数=" + rejections.size()
                + "，结果=" + describe(outcomes));
        assertEquals(400, rejections.get(0).getCode());
        assertTrue(rejections.get(0).getMessage().contains("可提余额不足"),
                "拒绝理由必须是可提余额不足，实际=" + rejections.get(0).getMessage());

        // 资金口径复核：frozenTotal 不超 settledTotal，冻结台账恰一行
        BigDecimal frozen = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM t_merchant_withdrawal WHERE mer_id=? AND status=0",
                BigDecimal.class, merId);
        BigDecimal settled = jdbc.queryForObject(
                "SELECT COALESCE(SUM(net_amount),0) FROM t_merchant_earning WHERE mer_id=? "
                        + "AND status IN ('SETTLED','REVERSED')", BigDecimal.class, merId);
        assertTrue(frozen.compareTo(settled) <= 0,
                "在途冻结 " + frozen + " 不得超过已结算净额 " + settled + "（双花即超）");
        assertEquals(0, frozen.compareTo(APPLY_AMOUNT), "冻结总额必须等于唯一成功那笔");
        Integer freezeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_finance_ledger WHERE mer_id=? AND biz_type='withdraw_freeze'",
                Integer.class, merId);
        assertEquals(1, freezeRows, "并发两笔只落一行冻结台账");
    }

    // ==================== 装配与数据 ====================

    /**
     * 真实事务语义：TransactionInterceptor 按 @Transactional 开启事务（等价生产代理），
     * JdbcTemplate 与 MyBatis 会话经 DataSourceUtils 共享同一线程连接。
     */
    private WithdrawalService buildTransactionalService() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("withdrawal-it",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(MerchantWithdrawalMapper.class);
        configuration.addMapper(FinanceLedgerMapper.class);
        SqlSessionTemplate sqlSession = new SqlSessionTemplate(
                new MybatisSqlSessionFactoryBuilder().build(configuration));

        MerchantService merchantService = mock(MerchantService.class);
        Merchant m = new Merchant();
        m.setMerId(merId);
        m.setUserId(1L);
        // 锁步栅栏：第一笔停在快照读建立处，等第二笔的读视图建好（见测试方法注释）。
        // mock 的 getByUser 本身不读库，而生产 mustMerchant 的 t_merchant 普通一致性读
        // 正是 RR 读视图的创建点——这里补一条真实一致性读，忠实还原生产时序。
        AtomicInteger merchantLookups = new AtomicInteger();
        CountDownLatch snapshotReady = new CountDownLatch(1);
        when(merchantService.getByUser(anyLong())).thenAnswer(inv -> {
            jdbc.queryForObject("SELECT mer_id FROM t_merchant WHERE mer_id = ?", Long.class, merId);
            if (merchantLookups.incrementAndGet() == 1) {
                if (!snapshotReady.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发对齐超时：另一笔未建立快照读");
                }
            } else {
                snapshotReady.countDown();
            }
            return m;
        });
        // 两笔并发各取一个号，避开 uk_withdraw_no 冲突淹没余额判定
        SequenceService sequenceService = mock(SequenceService.class);
        when(sequenceService.nextNo(anyString(), eq(4))).thenReturn("0001", "0002");

        // 通道 B：提现与结算打款共用同一资金锁服务（同一 SQL 的 t_merchant 行锁）
        MerchantFundLockService fundLock = new MerchantFundLockService(jdbc,
                sqlSession.getMapper(MerchantWithdrawalMapper.class));
        WithdrawalService target = new WithdrawalService(sqlSession.getMapper(MerchantWithdrawalMapper.class),
                sqlSession.getMapper(FinanceLedgerMapper.class), merchantService, sequenceService,
                mock(com.lanlink.shopping.module.attachment.service.AttachmentService.class), fundLock);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (WithdrawalService) proxy.getProxy();
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
            st.execute("CREATE TABLE IF NOT EXISTS t_merchant_withdrawal ("
                    + " id BIGINT PRIMARY KEY AUTO_INCREMENT, mer_id BIGINT NOT NULL,"
                    + " withdraw_no VARCHAR(40) NOT NULL, amount DECIMAL(14,2) NOT NULL,"
                    + " balance_snapshot DECIMAL(14,2) NOT NULL, bank_account_name VARCHAR(64) NOT NULL,"
                    + " bank_account_no_masked VARCHAR(64) NOT NULL, bank_name VARCHAR(100) NOT NULL,"
                    + " status TINYINT NOT NULL DEFAULT 0, apply_note VARCHAR(255) NULL,"
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

    private void seedMerchantWithSettledEarnings() {
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO t_merchant (ent_id, user_id, review_status) VALUES (990001, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, -RUN_STAMP);
            return ps;
        }, keyHolder);
        merId = keyHolder.getKey().longValue();
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO t_merchant_earning (mer_id, order_no, item_id, prod_id, type, "
                            + "refund_seq, gross_amount, commission_rate, commission_amount, net_amount, "
                            + "status, order_subtotal, order_total) VALUES (?,?,?,?,?,0,?,?,?,?,?,?,?)",
                    merId, "IT" + RUN_STAMP + "-" + i, i, 1L, "EARN",
                    new BigDecimal("200.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("200.00"), "SETTLED",
                    new BigDecimal("200.00"), new BigDecimal("200.00"));
        }
    }

    /**
     * 连不上本机 MySQL（未起库，或账号/密码与开发库不符）视为环境前提不满足：
     * 按类注释跳过而不是报错。凭证就绪（如 start-local-env.ps1 已注入 SPRING_DATASOURCE_*）
     * 时仍真实执行。跳过原因只带主机与账号，不回显密码。
     */
    private static Connection openServerConnectionOrSkip() throws SQLException {
        try {
            return DriverManager.getConnection(serverUrl(), USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.abort("本机 MySQL 不可用（" + HOST + ":" + PORT + "，user=" + USER
                    + "）：" + e.getMessage()
                    + "；起库或注入 SPRING_DATASOURCE_* 环境变量后本防双花回归真实执行");
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
            if (o instanceof MerchantWithdrawal w) {
                parts.add("成功(no=" + w.getWithdrawNo() + ", snapshot=" + w.getBalanceSnapshot() + ")");
            } else if (o instanceof BusinessException e) {
                parts.add(e.getCode() + ":" + e.getMessage());
            } else {
                parts.add(String.valueOf(o));
            }
        }
        return parts.toString();
    }
}
