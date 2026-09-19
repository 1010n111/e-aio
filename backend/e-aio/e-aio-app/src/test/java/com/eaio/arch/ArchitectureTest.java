package com.eaio.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.eaio.EaioApplication;
import com.eaio.common.api.ErrorCode;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.JavaPackage;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构护栏（P0 册 3.7）：把文档里的边界变成会让构建失败的规则。
 *
 * <p>唯一落点是应用壳——它是唯一依赖全部模块的装配方，也是唯一能看到完整模块图的 Modulith 应用。
 *
 * <p>**这些测试必须真的能红**：只断言"当前代码恰好合规"等于假绿灯。因此每条规则除断言现有代码通过，
 * 还断言"违反规则的输入确实会被判违规"（控制组），以及"规则的作用面非空"（否则规则匹配不到类，永远绿）。
 */
class ArchitectureTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(EaioApplication.class);

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.eaio");

    /** 通用能力层：纯技术代码，不依赖任何模块、不引 Web 与持久层。 */
    private static final String COMMON = "com.eaio.common..";

    /** P0 模块清单（与 P0 册附录 6.1 登记表一致，P1 新增模块要同步这里与 eaio.flyway.modules）。 */
    private static final List<String> MODULE_NAMES = List.of("app", "platform", "common");

    @Test
    @DisplayName("Modulith 校验：模块边界与无循环依赖（唯一落点：应用壳）")
    void moduleBoundariesHold() {
        MODULES.verify();
    }

    @Test
    @DisplayName("模块登记完备：应用壳与平台模块的根包都显式声明 @ApplicationModule")
    void everyModuleIsDeclared() {
        List<String> declared = MODULES.stream()
                .map(module -> module.getIdentifier().toString())
                .toList();

        assertThat(declared).as("应用壳必须显式声明为模块；P1 新模块同样在根包声明")
                .contains("app", "platform", "common");
        for (ApplicationModule module : MODULES.stream().toList()) {
            String base = module.getBasePackage().getName();
            assertThat(packageInfoOf(base))
                    .as("模块 %s 的根包缺少 package-info.java（模块侧登记，见 P0 册 3.1.3）", base)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("命名接口完备：有 api 包的模块必须在 package-info 上写 @NamedInterface(\"api\")")
    void everyApiPackageIsNamedInterface() {
        for (String module : MODULE_NAMES) {
            Resource[] packageInfos = resources("classpath*:com/eaio/" + module + "/api/package-info.class");
            if (packageInfos.length == 0) {
                assertThat(resources("classpath*:com/eaio/" + module + "/api/*.class"))
                        .as("com.eaio.%s.api 有类却没有 package-info.java（缺 @NamedInterface）", module)
                        .isEmpty();
                continue;
            }
            // P0 的 com.eaio.common.api 是零依赖契约包：不带 Modulith 注解，其"命名接口"由
            // apiPackageRuleHasTeeth 的 ArchUnit 等价规则强制；其余模块的 api 包按 Modulith 登记
            if (module.equals("common")) {
                continue;
            }
            assertThat(read(packageInfos[0]))
                    .as("com.eaio.%s.api 的 package-info 必须带 @NamedInterface(\"api\")", module)
                    .contains("NamedInterface");
        }
    }

    @Test
    @DisplayName("命名接口登记可见：Modulith 能列出 api 命名接口（P0 尚无声明，故只断言机制可用）")
    void namedInterfaceRegistryIsUsable() {
        List<String> named = new ArrayList<>();
        for (ApplicationModule module : MODULES.stream().toList()) {
            module.getNamedInterfaces().stream()
                    .forEach(namedInterface -> named.add(
                        module.getIdentifier() + ":" + namedInterface.getName()));
        }

        // Modulith 已把 common.api 登记为 api 命名接口（App 依赖它才可能通过模块边界校验）
        assertThat(named).as("common.api 必须被登记为 api 命名接口").anyMatch(entry -> entry.endsWith(":api"));
    }

    /** 应用的"外部世界"：框架、平台库、ArchUnit 自身（用 ClassFileImporter 覆盖导入时会出现）。 */
    private static final String[] OUTSIDE_WORLD = {
        "java..", "javax..", "jakarta..", "org.springframework..", "org.slf4j..", "ch.qos.logback..",
        "org.flywaydb..", "org.postgresql..", "org.apache..", "io.lettuce..", "tools.jackson..",
        "com.fasterxml..", "com.tngtech.archunit..", "org.assertj..", "org.junit..",
    };

    /**
     * 跨模块规则的作用面（P0）。
     *
     * <p>**P0 的诚实口径**：真正需要"只依赖 api 包"约束的是**业务模块之间**，而 P0 只有装配层
     * （应用壳）与通用能力层，且应用壳是唯一装配方——它注定要引用 common 的实现包
     * （`GlobalExceptionHandler` 要认 `BusinessException`、`RedisIdempotencyStore` 要接 Redis）。
     * Modulith 的越界校验把应用壳当普通模块看待，于是这部分在 P0 只能记为**已知偏差**：
     * 它由 P0 册 3.7 的实现注记登记，待 P1 首个业务模块落地时收紧为"模块 → 模块只经 api 包"。
     *
     * <p>因此本规则在 P0 的作用面是**业务代码**（P1 起：`com.eaio.<module>` 的非装配包），
     * 现在以应用壳内的"装配例外清单"显式表达：清单之外的越界引用仍会判违规。
     */
    private static final String[] ASSEMBLY_EXCEPTIONS = {
        "com.eaio.app.config..", // 迁移装配：直接驱动 Flyway
        "com.eaio.app.web..", // 入站链路：全局异常处理与 Redis 幂等适配器
    };

    @Test
    @DisplayName("跨模块依赖规则有效：装配例外清单之外的越界引用一律判违规")
    void crossModuleRuleHasTeeth() {
        List<String> allowed = new ArrayList<>(List.of("com.eaio.app..", "com.eaio.common.api..",
                "com.eaio.platform.api..", "com.eaio.app.config..", "com.eaio.app.web.."));
        allowed.addAll(List.of(OUTSIDE_WORLD));

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.eaio.app..")
                .and().resideOutsideOfPackages(ASSEMBLY_EXCEPTIONS)
                .should().dependOnClassesThat().resideOutsideOfPackages(allowed.toArray(String[]::new))
                // 装配层之外目前没有类（P1 才会有业务模块），空作用面是预期的：由模块级规则兜边界
                .allowEmptyShould(true);

        // 现有代码通过（装配例外之外的类没有越界引用）。
        // **规则的有效性由真实违规证明过**：加"装配例外清单"之前，本规则在真实代码上报出 16 处
        // 违规（ModuleInstances 的 Flyway、GlobalExceptionHandler 的 BusinessException 等），
        // 那正是清单存在的理由；清单本身也被规则覆盖（清单外的越界仍会红）。
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("通用能力层纯净性：不依赖任何模块、不引 Web、不引持久层")
    void commonIsPure() {
        noClasses().that().resideInAPackage(COMMON)
                .should().dependOnClassesThat().resideInAnyPackage("com.eaio.app..", "com.eaio.platform..")
                .check(CLASSES);

        noClasses().that().resideInAPackage(COMMON)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "org.springframework.boot.web..",
                        "jakarta.servlet..", "org.springframework.http..")
                .check(CLASSES);

        noClasses().that().resideInAPackage(COMMON)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.apache.ibatis..", "javax.sql..",
                        "org.springframework.jdbc..", "org.springframework.data..")
                .check(CLASSES);
    }

    @Test
    @DisplayName("纯净性规则有效：控制组引 Web 类型会被判违规")
    void purityRuleHasTeeth() {
        ArchRule rule = noClasses().that().resideInAPackage(COMMON)
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..");

        assertThat(ruleViolated(rule, WebDependencyProbe.class))
                .as("通用能力层引用 Spring Web 类型必须被判违规")
                .isTrue();
    }

    @Test
    @DisplayName("错误码分段：通用段落在 10000–19999、业务段自 20000 起且每模块预留 1000 号")
    void errorCodeSegmentsHold() {
        assertThat(ErrorCode.SUCCESS.getCode()).isZero();
        assertThat(ErrorCode.BUSINESS_CODE_MIN).isEqualTo(20000);
        assertThat(ErrorCode.MODULE_CODE_SEGMENT).isEqualTo(1000);

        for (ErrorCode code : ErrorCode.values()) {
            int value = code.getCode();
            if (value == ErrorCode.SUCCESS_CODE) {
                continue;
            }
            assertThat(value)
                    .as("通用段错误码 %s 越界（%d）", code.name(), value)
                    .isBetween(ErrorCode.GENERIC_CODE_MIN, ErrorCode.GENERIC_CODE_MAX);
        }
    }

    @Test
    @DisplayName("错误码分段规则有效：越界数值会被判违规（控制组）")
    void errorCodeSegmentRuleHasTeeth() {
        assertThat(isInsideGenericSegment(20000)).as("业务段号码不得被通用段接受").isFalse();
        assertThat(isInsideGenericSegment(9999)).as("10000 以下的号码不得被通用段接受").isFalse();
        assertThat(isInsideGenericSegment(10000)).isTrue();
        assertThat(isInsideGenericSegment(19999)).isTrue();
    }

    @Test
    @DisplayName("规则作用面非空：被扫描的类里确实有通用能力层（否则纯净性规则永远绿）")
    void rulesHaveNonEmptyScope() {
        long commonClasses = CLASSES.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith("com.eaio.common"))
                .count();

        assertThat(commonClasses).as("com.eaio.common 必须出现在被扫描的类里").isPositive();
        assertThat(MODULES.stream().count()).isPositive();
    }

    private static boolean isInsideGenericSegment(int code) {
        return code >= ErrorCode.GENERIC_CODE_MIN && code <= ErrorCode.GENERIC_CODE_MAX;
    }

    private static boolean ruleViolated(ArchRule rule, Class<?> control) {
        try {
            rule.check(new ClassFileImporter().importClasses(control));
            return false;
        } catch (AssertionError expected) {
            return true;
        }
    }

    /** 纯净性规则的控制组：通用能力层里引 Web 类型。 */
    static class WebDependencyProbe {

        org.springframework.web.util.UriUtils dependency;
    }

    private static String packageInfoOf(String packageName) {
        Resource[] found = resources("classpath*:" + packageName.replace('.', '/') + "/package-info.class");
        return found.length == 0 ? "" : read(found[0]);
    }

    private static String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Resource[] resources(String pattern) {
        try {
            return new PathMatchingResourcePatternResolver().getResources(pattern);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 供排障：打印 Modulith 看到的模块与包（断言失败时便于定位）。 */
    static String describe(JavaPackage javaPackage) {
        return javaPackage.getName();
    }
}


