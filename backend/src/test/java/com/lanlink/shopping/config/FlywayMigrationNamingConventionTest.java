package com.lanlink.shopping.config;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5：迁移文件命名规范校验（扫描真实 db-migrations 目录，不连数据库）。
 * 规范（自 V2026_12 起执行的强制格式，V2026_10/V2026_11 为既有文件需符合同一形态）：
 *   V{YYYY}_{MM}__{snake_case 描述}.sql —— 版本按"年_月"编码，Flyway 解析下划线为点（2026_12 → 2026.12）。
 * 另外把 baseline 约束编码为回归网：baseline-version 必须盖住全部既有迁移、且小于
 * 下一月版本，否则新迁移会被 Below Baseline 静默跳过（application.yml flyway 注释中的语义约束）。
 */
class FlywayMigrationNamingConventionTest {

    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("^V(\\d{4})_(\\d{2})__([a-z0-9_]+)\\.sql$");

    private static Path migrationsDir() {
        // Maven surefire 工作目录为模块根（backend/）；CI 与本地一致。双候选兼容从仓库根执行的场景。
        for (String candidate : new String[]{"src/main/resources/db-migrations",
                "backend/src/main/resources/db-migrations"}) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new AssertionError("找不到 db-migrations 目录（工作目录应位于 backend/ 模块根）");
    }

    private static String baselineVersion() {
        String value = FlywayConfigLoadTest.loadYaml("application.yml")
                .getProperty("spring.flyway.baseline-version");
        assertNotNull(value, "application.yml 必须声明 spring.flyway.baseline-version");
        return value;
    }

    private record MigrationFile(String fileName, MigrationVersion version, int year, int month, String description) {
    }

    private static List<MigrationFile> resolveMigrations() throws IOException {
        try (Stream<Path> files = Files.list(migrationsDir())) {
            List<Path> sqls = files
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
            assertFalse(sqls.isEmpty(), "db-migrations 目录下必须存在迁移 SQL");
            return sqls.stream().map(p -> {
                String name = p.getFileName().toString();
                Matcher m = FILENAME_PATTERN.matcher(name);
                assertTrue(m.matches(), "迁移文件命名不符合 V{YYYY}_{MM}__{snake_case}.sql 规范: " + name);
                int year = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                assertTrue(month >= 1 && month <= 12, "版本月份必须在 1-12: " + name);
                String description = m.group(3);
                assertFalse(description.isEmpty(), "迁移描述不能为空: " + name);
                // 实证 Flyway 的下划线解析语义：V2026_11 → 2026.11（等价点分版本）
                assertEquals(year + "." + month, MigrationVersion.fromVersion(year + "_" + month).getVersion(),
                        "Flyway 版本解析应把下划线视为点分隔: " + name);
                return new MigrationFile(name, MigrationVersion.fromVersion(year + "_" + month), year, month, description);
            }).toList();
        }
    }

    @Test
    void allMigrationFilesFollowNamingConvention() throws IOException {
        List<MigrationFile> migrations = resolveMigrations();
        Set<MigrationVersion> seen = new HashSet<>();
        for (MigrationFile file : migrations) {
            assertTrue(seen.add(file.version()), "迁移版本重复: " + file.fileName());
            assertFalse(file.description().matches(".*__.*"), "描述中不应出现连续下划线: " + file.fileName());
        }
    }

    @Test
    void baselineIsBelowAllExistingMigrationsSoTheyStillRun() throws IOException {
        MigrationVersion baseline = MigrationVersion.fromVersion(baselineVersion());
        List<MigrationFile> migrations = resolveMigrations();
        for (MigrationFile file : migrations) {
            // 关键约束：baseline 必须【低于】每个存量迁移，否则 Flyway 判为 Below Baseline
            // 并静默跳过——历史值 2026.11.1 就让 V2026_11（2026.11）永不执行。
            assertTrue(file.version().compareTo(baseline) > 0,
                    "既有迁移 " + file.fileName() + " 必须高于 baseline(" + baselineVersion() + ")，"
                            + "否则会被 Below Baseline 静默跳过");
        }
        MigrationFile newest = migrations.get(migrations.size() - 1);
        MigrationVersion nextMonth = MigrationVersion.fromVersion(nextMonthVersion(newest.year(), newest.month()));
        assertTrue(baseline.compareTo(nextMonth) < 0,
                "baseline-version(" + baselineVersion() + ") 必须小于下一月版本 " + nextMonth.getVersion()
                        + "，否则下月迁移会被 Below Baseline 静默跳过（例如误设为日期串 20261111.0.0 时）");
    }

    /** 年月滚动：2026_11 → 2026_12；2026_12 → 2027_01。 */
    private static String nextMonthVersion(int year, int month) {
        return month == 12 ? (year + 1) + ".1" : year + "." + (month + 1);
    }
}
