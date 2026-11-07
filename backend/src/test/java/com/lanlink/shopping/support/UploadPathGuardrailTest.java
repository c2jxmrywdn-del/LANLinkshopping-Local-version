package com.lanlink.shopping.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传路径护栏。
 *
 * <p>对象存储抽象层（common/storage）建立后，业务代码不得再自行拼接本地磁盘路径或
 * "/uploads/" URL —— 否则会出现「新代码走抽象、旧代码走裸路径」的双轨，
 * 切换 provider 时静默漏掉一批上传点。
 *
 * <p>本仓库有过「一处修复被并行重构还原」的先例，所以这里用可执行规则而不是口头约定：
 * 存量裸路径文件被冻结在 LEGACY 清单里，只允许减少；新增文件命中模式即失败。
 */
class UploadPathGuardrailTest {

    /**
     * 冻结的存量违规（相对 backend/src/main/java）。迁移一个删一条，禁止新增。
     * 清单由本测试首次跑红时的真实输出固化，不是手写的期望值。
     */
    private static final Set<String> LEGACY = Set.of(
            "com/lanlink/shopping/config/WebConfig.java",
            "com/lanlink/shopping/controller/MerchantController.java",
            "com/lanlink/shopping/controller/ProductController.java",
            "com/lanlink/shopping/controller/UserController.java",
            "com/lanlink/shopping/module/productreview/service/ProductReviewVisionService.java",
            "com/lanlink/shopping/service/ChatMediaService.java",
            "com/lanlink/shopping/service/MerchantChatService.java");

    /**
     * 允许持有上传字面量的存储基础设施（豁免前缀，不是存量记账）。
     * 含 out/storage-mq-plan.md 任务 A2 规划中的 Storage* 新类——它们的职责就是集中持有
     * "/api/uploads/" 兼容前缀，不放行会误伤并行施工的 mvn verify 门禁。
     */
    private static final List<String> SANCTIONED_PREFIXES = List.of(
            "com/lanlink/shopping/common/storage/",
            "com/lanlink/shopping/common/Storage");

    private static final List<String> FORBIDDEN = List.of(
            "System.getProperty(\"user.dir\")",
            "\"/uploads/",
            "\"/api/uploads/");

    @Test
    void businessCodeMustNotBuildRawUploadPaths() throws IOException {
        Path sourceRoot = Paths.get(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(sourceRoot), "未找到源码根目录：" + sourceRoot.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String rel = sourceRoot.relativize(f).toString().replace('\\', '/');
                if (SANCTIONED_PREFIXES.stream().anyMatch(rel::startsWith)) continue;
                String src = Files.readString(f, StandardCharsets.UTF_8);
                for (String pattern : FORBIDDEN) {
                    if (src.contains(pattern)) {
                        offenders.add(rel + "  ->  " + pattern);
                    }
                }
            }
        }

        List<String> unexpected = offenders.stream()
                .filter(o -> LEGACY.stream().noneMatch(o::startsWith))
                .toList();
        assertTrue(unexpected.isEmpty(),
                () -> "上传路径必须经 common/storage 抽象。违规文件清单：\n" + String.join("\n", unexpected));
    }
}
