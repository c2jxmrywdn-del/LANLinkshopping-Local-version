package com.lanlink.shopping.support;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 技术服务模块架构边界（可执行规则）。
 *
 * <p>只对 techservice 包生效，不改造存量遗留包；新增违规立即失败。
 * 规则目标：Web 层不碰持久化、领域层不依赖框架、服务层不反向依赖 Web、
 * 子包无循环、消息发送只允许出现在事件监听器。
 */
class TechServiceArchitectureTest {

    private static final JavaClasses TECH =
            new ClassFileImporter().importPackages("com.lanlink.shopping.module.techservice");

    @Test
    void controllersMustNotTouchEntitiesOrMappers() {
        ArchRule rule = noClasses().that().resideInAPackage("..techservice.controller..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..techservice.entity..", "..techservice.mapper..");
        rule.check(TECH);
    }

    @Test
    void domainLayerMustStayFrameworkFree() {
        // DDD-lite：允许领域组件读取同模块持久化实体，但不得依赖 Mapper/Service/Controller/Spring
        ArchRule rule = noClasses().that().resideInAPackage("..techservice.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..techservice.mapper..",
                        "..techservice.service..", "..techservice.controller..",
                        "org.springframework..");
        rule.check(TECH);
    }

    @Test
    void servicesMustNotDependOnControllers() {
        ArchRule rule = noClasses().that().resideInAPackage("..techservice.service..")
                .should().dependOnClassesThat().resideInAPackage("..techservice.controller..");
        rule.check(TECH);
    }

    @Test
    void messageSendingOnlyAllowedInEventListeners() {
        ArchRule rule = noClasses().that().resideInAPackage("..techservice.service..")
                .should().dependOnClassesThat().haveSimpleName("MessageService");
        rule.check(TECH);
    }

    @Test
    void subPackagesMustBeFreeOfCycles() {
        ArchRule rule = slices().matching("com.lanlink.shopping.module.techservice.(*)..")
                .should().beFreeOfCycles();
        rule.check(TECH);
    }
}
