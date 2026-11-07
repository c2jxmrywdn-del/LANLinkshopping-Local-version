package com.lanlink.shopping.support;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 遗留模块架构边界（硬规则，无冻结基线）。
 *
 * <p>阶段 3 审计确认三个模块的「Controller→entity/mapper」「Service→controller」「子包环」
 * 违规数均为 0（实体外泄端点已改为 DTO），因此直接启用硬规则，新增违规立即失败。
 */
class LegacyModuleArchitectureTest {

    private static final String[] MODULES = {"pricemonitor", "aitool", "campaign"};

    private static final JavaClasses LEGACY = new ClassFileImporter().importPackages(
            "com.lanlink.shopping.module.pricemonitor",
            "com.lanlink.shopping.module.aitool",
            "com.lanlink.shopping.module.campaign");

    @Test
    void controllersMustNotTouchPersistenceOrEntities() {
        for (String module : MODULES) {
            ArchRule rule = noClasses().that()
                    .resideInAPackage("..module." + module + ".controller..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..module." + module + ".entity..",
                            "..module." + module + ".mapper..");
            rule.check(LEGACY);
        }
    }

    @Test
    void servicesMustNotDependOnControllers() {
        for (String module : MODULES) {
            ArchRule rule = noClasses().that()
                    .resideInAPackage("..module." + module + ".service..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..module." + module + ".controller..");
            rule.check(LEGACY);
        }
    }

    @Test
    void subPackagesMustBeFreeOfCycles() {
        for (String module : MODULES) {
            ArchRule rule = slices().matching("com.lanlink.shopping.module." + module + ".(*)..")
                    .should().beFreeOfCycles();
            rule.check(LEGACY);
        }
    }
}
