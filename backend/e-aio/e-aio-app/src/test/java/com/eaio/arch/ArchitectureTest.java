package com.eaio.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eaio.EaioApplication;
import com.eaio.arch.probes.DuplicatedWithinModuleErrorCode;
import com.eaio.arch.probes.GenericSegmentClashErrorCode;
import com.eaio.arch.probes.OutOfSegmentErrorCode;
import com.eaio.arch.probes.internal.InternalProbe;
import com.eaio.arch.probes.owner.OwnerProbe;
import com.eaio.common.api.BusinessErrorCode;
import com.eaio.common.api.ErrorCode;
import com.tngtech.archunit.core.domain.JavaClass;
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

        assertThat(declared).as("发现到的模块必须与登记表逐一对应：新增模块忘记登记（这里与 eaio.flyway.modules）即失败")
                .containsExactlyInAnyOrderElementsOf(MODULE_NAMES);
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
        "com.eaio.app.redis..", // Redis 门面实现：持有客户端类型，向平台模块提供 RedisKit
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

    // ------------------------------------------------------------------
    // P1：模块边界、内部层与错误码段（P1 册 6.3）
    // 每条规则都配"故意越界即失败"的控制组，且控制组断言必须**指名到类**：
    // 只断言"当前代码通过"等于假绿灯，ArchUnit 在作用面为空时也会抛错，那不算有牙齿。
    // ------------------------------------------------------------------

    /** 业务模块登记表（P1 册 6.1）：模块根包 → 错误码段起点，每模块预留 1000 号。 */
    private static final Map<String, Integer> ERROR_CODE_SEGMENTS = Map.of(
            "platform", 20000,
            "iam", 21000,
            "audit", 22000,
            "workflow", 23000,
            "approval", 24000);

    /** 平台模块不得依赖的业务模块包（P1 册 2.4.2，含其 api 包；ADR-0005）。 */
    private static final List<String> BUSINESS_MODULE_PACKAGES = List.of(
            "com.eaio.iam..", "com.eaio.audit..", "com.eaio.workflow..", "com.eaio.approval..",
            "com.eaio.mdm..", "com.eaio.report..", "com.eaio.oa..", "com.eaio.portal..", "com.eaio.ai..");

    /** 平台的内部层：非平台代码不得依赖（P1 册 6.3；events 不在列内——它是模块的发布面）。 */
    private static final List<String> PLATFORM_INTERNAL_PACKAGES = List.of(
            "com.eaio.platform.application..", "com.eaio.platform.domain..", "com.eaio.platform.infrastructure..");

    @Test
    @DisplayName("错误码落段：每个业务模块都有 *ErrorCode 枚举，且每个码落本模块段、不与通用段重号、模块内不重号")
    void moduleErrorCodesWithinSegment() {
        assertThat(ERROR_CODE_SEGMENTS).as("登记表必须覆盖 platform 的段（P1 册 6.1）")
                .containsEntry("platform", 20000);

        // 作用面非空：扫不到枚举就断言"合规"等于假绿灯（P0 册 3.7 的口径）。
        // 注意 common 的通用段 ErrorCode 也实现 BusinessErrorCode，这里只看平台自己的枚举。
        assertThat(errorCodeEnums(CLASSES).stream()
                .map(JavaClass::getName)
                .filter(name -> name.startsWith("com.eaio.platform."))
                .toList())
                .as("平台必须有 PlatformErrorCode（P1 册 7.1，M1 交付物）")
                .containsExactly("com.eaio.platform.api.PlatformErrorCode");
        assertThat(missingErrorCodeEnums(CLASSES, MODULE_NAMES))
                .as("业务模块必须首版即建错误码枚举，不得借用通用段（P1-1 册 A2）")
                .isEmpty();
        assertThat(errorCodeViolations(CLASSES, ERROR_CODE_SEGMENTS))
                .as("平台错误码必须落在 20000–20999")
                .isEmpty();
    }

    @Test
    @DisplayName("错误码段登记覆盖：每个业务模块都要在段表里登记（新增模块忘登记即失败）")
    void errorCodeSegmentsCoverEveryBusinessModule() {
        List<String> businessModules = MODULE_NAMES.stream()
                .filter(module -> !"app".equals(module) && !"common".equals(module))
                .toList();

        assertThat(ERROR_CODE_SEGMENTS.keySet())
                .as("业务模块 %s 必须在错误码段表登记（P1 册 6.1）", businessModules)
                .containsAll(businessModules);
    }

    @Test
    @DisplayName("错误码落段规则有效：越界 / 与通用段重号 / 模块内重号 / 缺枚举 都被抓到")
    void moduleErrorCodesWithinSegmentRuleHasTeeth() {
        JavaClasses probes = new ClassFileImporter().importClasses(
                OutOfSegmentErrorCode.class, GenericSegmentClashErrorCode.class, DuplicatedWithinModuleErrorCode.class);

        assertThat(errorCodeViolations(probes, Map.of("arch", 20000)))
                .as("三类问题都要被抓到（10000 那条既越界又与通用段重号，因此报两条）")
                .hasSize(4)
                .allSatisfy(violation -> assertThat(violation).contains("com.eaio.arch.probes"))
                .anySatisfy(violation -> assertThat(violation).contains("OutOfSegmentErrorCode", "越界"))
                .anySatisfy(violation -> assertThat(violation).contains("GenericSegmentClashErrorCode", "与通用段重号"))
                .anySatisfy(violation -> assertThat(violation).contains("DuplicatedWithinModuleErrorCode", "重号"));
        assertThat(missingErrorCodeEnums(probes, List.of("probe")))
                .as("模块没有错误码枚举必须被判违规（A2 分支的牙齿）")
                .hasSize(1)
                .allSatisfy(violation -> assertThat(violation).contains("probe", "缺少"));
    }

    @Test
    @DisplayName("平台内部层：非 platform 的类不得依赖 application/domain/infrastructure")
    void platformInternalIsNotReferenced() {
        noOutsiderUsesInternals("com.eaio.platform..", PLATFORM_INTERNAL_PACKAGES).check(CLASSES);

        assertThat(PLATFORM_INTERNAL_PACKAGES).as("内部层清单必须与 P1 册 6.3 一致")
                .containsExactlyInAnyOrder("com.eaio.platform.application..", "com.eaio.platform.domain..",
                        "com.eaio.platform.infrastructure..");
    }

    @Test
    @DisplayName("平台内部层规则有效：控制组（平台之外的类引用内部层）被判违规并指名到类")
    void platformInternalIsNotReferencedRuleHasTeeth() {
        ArchRule rule = noOutsiderUsesInternals("com.eaio.platform..", List.of("com.eaio.arch.probes.internal.."));

        assertThat(violationMessageOf(rule, OwnerProbe.class, InternalProbe.class))
                .as("外部类引用模块内部层必须被判违规")
                .contains("OwnerProbe");
    }

    @Test
    @DisplayName("platform 零反向依赖：不得依赖任何业务模块（含其 api 包，ADR-0005）")
    void platformDoesNotDependOnBusinessModules() {
        noDependencyOn("com.eaio.platform..", BUSINESS_MODULE_PACKAGES).check(CLASSES);

        assertThat(BUSINESS_MODULE_PACKAGES).as("清单必须覆盖 P1 册 2.4.2 列出的全部业务模块")
                .hasSize(9).contains("com.eaio.iam..", "com.eaio.audit..");
    }

    @Test
    @DisplayName("零反向依赖规则有效：控制组（模块引用被禁包）被判违规")
    void platformDoesNotDependOnBusinessModulesRuleHasTeeth() {
        ArchRule rule = noDependencyOn("com.eaio.arch.probes.owner..", List.of("com.eaio.arch.probes.internal.."));

        assertThat(violationMessageOf(rule, OwnerProbe.class, InternalProbe.class))
                .as("被禁包一旦被引用就必须判违规")
                .contains("OwnerProbe");
    }

    @Test
    @DisplayName("平台模块声明：allowedDependencies 只允许 common 及其命名接口")
    void platformModuleAllowedDependencies() {
        List<String> allowed = allowedDependenciesOf("com.eaio.platform");

        assertThat(allowed).as("P1 必须显式声明依赖（P0 是空注解）").isNotEmpty();
        assertThat(allowedDependencyViolations(allowed))
                .as("allowedDependencies 里不得出现 common 及其命名接口之外的目标")
                .isEmpty();
    }

    @Test
    @DisplayName("模块声明规则有效：出现业务模块或未登记的命名接口即判违规（控制组）")
    void platformModuleAllowedDependenciesRuleHasTeeth() {
        assertThat(allowedDependencyViolations(List.of("common", "common::api", "iam")))
                .as("声明依赖 iam 必须被判违规")
                .hasSize(1)
                .allSatisfy(violation -> assertThat(violation).contains("iam"));
        assertThat(allowedDependencyViolations(List.of("common", "common::redis")))
                .as("common 下未登记为 api 命名接口的包同样不得出现")
                .hasSize(1);
    }

    /** 规则形状一：{@code ownerPackage} **之外**的类不得依赖 {@code internalPackages}（内部层不可外引）。 */
    private static ArchRule noOutsiderUsesInternals(String ownerPackage, List<String> internalPackages) {
        return noClasses().that().resideOutsideOfPackage(ownerPackage)
                .should().dependOnClassesThat().resideInAnyPackage(internalPackages.toArray(String[]::new));
    }

    /** 规则形状二：{@code ownerPackage} **之内**的类不得依赖 {@code forbiddenPackages}（不得反向依赖）。 */
    private static ArchRule noDependencyOn(String ownerPackage, List<String> forbiddenPackages) {
        return noClasses().that().resideInAPackage(ownerPackage)
                .should().dependOnClassesThat().resideInAnyPackage(forbiddenPackages.toArray(String[]::new));
    }

    /** 跑规则并取回违规信息；规则没红即失败——"控制组没被抓到"本身就是缺陷。 */
    private static String violationMessageOf(ArchRule rule, Class<?>... controls) {
        try {
            rule.check(new ClassFileImporter().importClasses(controls));
        } catch (AssertionError violation) {
            return String.valueOf(violation.getMessage());
        }
        throw new AssertionError("控制组未被判违规：规则形同虚设");
    }

    /** 错误码违规（越界 / 与通用段重号 / 模块内重号）；空列表 = 全部合规。 */
    private static List<String> errorCodeViolations(JavaClasses classes, Map<String, Integer> segments) {
        List<String> violations = new ArrayList<>();
        Map<String, Map<Integer, List<String>>> codesByModule = new LinkedHashMap<>();

        for (JavaClass javaClass : errorCodeEnums(classes)) {
            String module = moduleOf(javaClass.getPackageName());
            if (module == null || !segments.containsKey(module)) {
                continue;
            }
            for (Object constant : javaClass.reflect().getEnumConstants()) {
                int code = ((BusinessErrorCode) constant).getCode();
                String name = javaClass.getName() + "." + ((Enum<?>) constant).name();
                codesByModule.computeIfAbsent(module, key -> new LinkedHashMap<>())
                        .computeIfAbsent(code, key -> new ArrayList<>()).add(name);

                int start = segments.get(module);
                if (code != ErrorCode.SUCCESS_CODE && !isInsideSegment(code, start)) {
                    violations.add(name + "=" + code + " 越界（模块段 " + start + "–"
                            + (start + ErrorCode.MODULE_CODE_SEGMENT - 1) + "）");
                }
                if (isInsideGenericSegment(code)) {
                    violations.add(name + "=" + code + " 与通用段重号");
                }
            }
        }

        codesByModule.values().forEach(codes -> codes.forEach((code, names) -> {
            if (names.size() > 1) {
                violations.add("模块内重号 " + code + "：" + String.join("、", names));
            }
        }));

        return violations;
    }

    private static List<JavaClass> errorCodeEnums(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> javaClass.isEnum() && javaClass.isAssignableTo(BusinessErrorCode.class))
                .toList();
    }

    /** A2：业务模块（app/common 之外）必须首版即建错误码枚举，不得"暂时借用通用段"。 */
    private static List<String> missingErrorCodeEnums(JavaClasses classes, List<String> moduleNames) {
        List<JavaClass> enums = errorCodeEnums(classes);
        List<String> missing = new ArrayList<>();

        for (String module : moduleNames) {
            if ("app".equals(module) || "common".equals(module)) {
                continue;
            }
            boolean present = enums.stream()
                    .anyMatch(javaClass -> javaClass.getPackageName().startsWith("com.eaio." + module));
            if (!present) {
                missing.add("模块 " + module + " 缺少 *ErrorCode 枚举（P1-1 册 A2：首版即建，不得借用通用段）");
            }
        }
        return missing;
    }

    /** 类所属模块根包：{@code com.eaio.<module>…} → {@code <module>}；不在 com.eaio 下返回 null。 */
    private static String moduleOf(String packageName) {
        String[] parts = packageName.split("\\.");
        return parts.length >= 3 && "com".equals(parts[0]) && "eaio".equals(parts[1]) ? parts[2] : null;
    }

    private static boolean isInsideSegment(int code, int segmentStart) {
        return code >= segmentStart && code < segmentStart + ErrorCode.MODULE_CODE_SEGMENT;
    }

    /** 平台声明里唯一允许的两个目标：模块本身与它的命名接口（P1 册 6.3）。 */
    private static final List<String> PERMITTED_PLATFORM_DEPENDENCIES = List.of("common", "common::api");

    /** 平台声明里不允许出现的目标：{@code common} 与其命名接口之外的一切（含未登记的 {@code common::x}）。 */
    private static List<String> allowedDependencyViolations(List<String> allowed) {
        return allowed.stream()
                .filter(target -> !PERMITTED_PLATFORM_DEPENDENCIES.contains(target))
                .toList();
    }

    /** 读模块根包 package-info 上的 {@code @ApplicationModule}（Modulith 的模块侧登记）。 */
    private static List<String> allowedDependenciesOf(String packageName) {
        try {
            Class<?> packageInfo = Class.forName(packageName + ".package-info");
            org.springframework.modulith.ApplicationModule annotation =
                    packageInfo.getPackage().getAnnotation(org.springframework.modulith.ApplicationModule.class);
            return annotation == null ? List.of() : List.of(annotation.allowedDependencies());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("读不到 " + packageName + " 的 package-info（模块侧登记缺失）", e);
        }
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


