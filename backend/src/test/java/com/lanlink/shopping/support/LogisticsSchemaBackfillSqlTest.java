package com.lanlink.shopping.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase3A/Phase2 回填 SQL 文本断言（out/carrier-expansion-design.md §5.1）：
 * mock JdbcTemplate verify SQL 文本——防 'DBL' 笔误回退与三列默认值守卫缺失。
 * （仓库无 H2/Testcontainers 基建，真库启动验证列入 P0 验收清单。）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogisticsSchemaBackfillSqlTest {

    @Mock
    private JdbcTemplate jdbc;

    private LogisticsPhase3ASchemaInitializer phase3a;
    private LogisticsPhase2SchemaInitializer phase2;

    @BeforeEach
    void setUp() {
        phase3a = new LogisticsPhase3ASchemaInitializer(jdbc);
        phase2 = new LogisticsPhase2SchemaInitializer(jdbc);
    }

    private void stubColumnExists() {
        // information_schema 检查全部返回"已存在"，跳过 ALTER TABLE
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        doNothing().when(jdbc).execute(anyString());
    }

    @Test
    void phase3aBackfillUsesDbCodeWithThreeColumnDefaultGuard() {
        stubColumnExists();

        phase3a.run(null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(1)).update(sqlCaptor.capture());
        List<String> sqls = sqlCaptor.getAllValues();

        // 德邦按 provider_code='DB' 定位（防 'DBL' 笔误回退）
        assertTrue(sqls.stream().anyMatch(s -> s.contains("provider_code = 'DB'") && s.contains("98.50")),
                "德邦回填 SQL 应按 provider_code='DB' 定位");

        // SF/JD/DB/ANE/KYE/BEST 全部带三列默认值联合守卫（该行从未被人工配置才回填）
        for (String code : List.of("SF", "JD", "DB", "ANE", "KYE", "BEST")) {
            String expectedGuard = "provider_code = '" + code + "' AND avg_eta_hours = 48 AND service_score = 92 AND sla_rate = 98.00";
            assertTrue(sqls.stream().anyMatch(s -> s.contains(expectedGuard)),
                    "回填 SQL 缺少 " + code + " 三列默认值守卫");
        }
        // 不再出现 OR provider_name LIKE 兜底（管理端改过名称的行会被覆盖）
        assertTrue(sqls.stream().filter(s -> s.contains("t_logistics_provider")).noneMatch(s -> s.contains("provider_name LIKE")),
                "回填 SQL 不得使用 provider_name LIKE 兜底（会覆盖人工配置）");
    }

    @Test
    void phase2BackfillTargetsByTemplateNameAndProviderCodeWithEmptyRuleGuard() {
        stubColumnExists();
        // 六个模板回填 UPDATE（5 变参）返回 1 → 触发置启用 UPDATE（2 变参）
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(Object.class), any(Object.class)))
                .thenReturn(1);

        phase2.run(null);

        // 回填 UPDATE：按 provider_code + template_name 业务键定位，带空配置守卫与 min_charge
        ArgumentCaptor<String> backfillCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(6)).update(backfillCaptor.capture(),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class));
        List<String> backfills = backfillCaptor.getAllValues();

        String sample = backfills.stream().filter(s -> s.contains("tier_rule_json")).findFirst().orElseThrow();
        assertTrue(sample.contains("p.provider_code=?"), "回填必须按 provider_code 业务键定位");
        assertTrue(sample.contains("t.template_name=?"), "回填必须按 template_name 业务键定位");
        assertTrue(sample.contains("t.tier_rule_json IS NULL OR t.tier_rule_json = ''"),
                "必须带空配置守卫（不覆盖人工配置）");
        assertTrue(sample.contains("t.min_charge=?"), "回填必须写 min_charge（兜底坏 JSON 静默返 0）");

        // 六个大件模板全部出现在回填定位参数里
        ArgumentCaptor<Object> paramCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, atLeast(6)).update(eq(sample), paramCaptor.capture(), paramCaptor.capture(),
                paramCaptor.capture(), paramCaptor.capture(), paramCaptor.capture());
        List<Object> params = paramCaptor.getAllValues();
        List<String> strings = params.stream().map(String::valueOf).toList();
        for (String code : List.of("KYE", "ANE", "BEST", "SF", "DB", "ZT")) {
            assertTrue(strings.contains(code), "回填参数缺少承运商 " + code);
        }

        // 置启用 UPDATE 以配置写入成功为前提（fail-safe）
        ArgumentCaptor<String> enableCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(6)).update(enableCaptor.capture(), any(Object.class), any(Object.class));
        String enableSql = enableCaptor.getAllValues().stream()
                .filter(s -> s.contains("status=1")).findFirst().orElseThrow();
        assertTrue(enableSql.contains("tier_rule_json IS NOT NULL"),
                "置启用必须以配置写入成功为前提（fail-safe）");
    }
}
