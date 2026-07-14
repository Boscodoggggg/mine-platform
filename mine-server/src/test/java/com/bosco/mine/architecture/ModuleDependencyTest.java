package com.bosco.mine.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 校验共享模块与运行时服务之间的依赖方向。
 */
@AnalyzeClasses(packages = "com.bosco.mine", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleDependencyTest {

    @ArchTest
    static final ArchRule SHARED_MODULES_MUST_NOT_DEPEND_ON_RUNTIME_SERVICES = noClasses()
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
            .allowEmptyShould(true)
            .because("共享契约必须独立于具体运行时服务");
}
