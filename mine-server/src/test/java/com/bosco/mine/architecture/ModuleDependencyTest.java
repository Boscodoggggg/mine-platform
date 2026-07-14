package com.bosco.mine.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验共享模块与运行时服务之间的依赖方向。
 */
class ModuleDependencyTest {

    private static final JavaClasses PROJECT_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.bosco.mine");

    private static final ArchRule SHARED_MODULES_MUST_NOT_DEPEND_ON_RUNTIME_SERVICES = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.bosco.mine.common..",
                    "com.bosco.mine.model..",
                    "com.bosco.mine.messaging..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.bosco.mine.server..",
                    "com.bosco.mine.gateway..",
                    "com.bosco.mine.telemetry..")
            .because("共享契约必须独立于具体运行时服务");

    @Test
    void 应扫描每个共享模块的生产类() {
        assertThat(PROJECT_CLASSES).anyMatch(javaClass ->
                javaClass.getPackageName().startsWith("com.bosco.mine.common"));
        assertThat(PROJECT_CLASSES).anyMatch(javaClass ->
                javaClass.getPackageName().startsWith("com.bosco.mine.model"));
        assertThat(PROJECT_CLASSES).anyMatch(javaClass ->
                javaClass.getPackageName().startsWith("com.bosco.mine.messaging"));
    }

    @Test
    void 共享模块不得依赖运行时服务() {
        SHARED_MODULES_MUST_NOT_DEPEND_ON_RUNTIME_SERVICES.check(PROJECT_CLASSES);
    }
}
