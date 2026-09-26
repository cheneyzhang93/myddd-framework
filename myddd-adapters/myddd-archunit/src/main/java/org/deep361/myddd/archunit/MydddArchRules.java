package org.deep361.myddd.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * myddd 架构守卫规则库（设计文档 §5.2）。
 *
 * <p>全部规则以业务工程的基础包（如 {@code com.deep361.order}）为锚点，包模式一律形如
 * {@code <base>..domain..}，因此单模块与多模块工程同样适用。
 *
 * <p>规则统一开启 {@code allowEmptyShould(true)}：分层是可选的，尚无 {@code ..api..} 包的工程
 * 不该因为"没有可检查的类"而构建失败（ArchUnit 1.5 对空选择集的默认行为是判失败）。
 */
public final class MydddArchRules {

    /** 领域层禁止出现的三方依赖家族（Spring / MyBatis / Jackson / JPA）。 */
    public static final List<String> FORBIDDEN_IN_DOMAIN = List.of(
            "org.springframework..",
            "com.baomidou..",
            "org.apache.ibatis..",
            "com.fasterxml.jackson..",
            "tools.jackson..",
            "jakarta.persistence..",
            "jakarta.transaction..");

    /** I 前缀匈牙利式接口名（{@code IOrder}）：I + 大写字母 + 小写字母，故 {@code IOTracer} 不匹配。 */
    private static final String I_PREFIX_PATTERN = ".*[.]I[A-Z][a-z][^.]*";

    private MydddArchRules() {
    }

    /**
     * 规则 1：domain 禁止依赖 Spring/MyBatis/Jackson/JPA/任何 adapter（领域纯净红线）。
     */
    public static ArchRule domainPurity(String basePackage) {
        return noClasses().that().resideInAPackage(layer(basePackage, "domain"))
                .should().dependOnClassesThat().resideInAnyPackage(forbiddenInDomain(basePackage))
                .because("领域层必须零框架依赖（设计文档 §5.2 规则 1）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 2：domain、application 禁止依赖 api、infrastructure。
     */
    public static ArchRule domainAndApplicationMustNotDependOnAdapters(String basePackage) {
        return noClasses().that().resideInAnyPackage(
                        layer(basePackage, "domain"), layer(basePackage, "application"))
                .should().dependOnClassesThat().resideInAnyPackage(
                        layer(basePackage, "api"), layer(basePackage, "infrastructure"))
                .because("内层不得依赖外层适配器（设计文档 §5.2 规则 2）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 3：application 禁止依赖 contract。
     */
    public static ArchRule applicationMustNotDependOnContract(String basePackage) {
        return noClasses().that().resideInAPackage(layer(basePackage, "application"))
                .should().dependOnClassesThat().resideInAPackage(layer(basePackage, "contract"))
                .because("application 面向领域与用例，不面向对外契约（设计文档 §5.2 规则 3）")
                .allowEmptyShould(true);
    }

    /** 层包模式：{@code <base>..<layer>..}，匹配 {@code base.layer} 及其任意深度子包。 */
    static String layer(String basePackage, String layer) {
        return basePackage + ".." + layer + "..";
    }

    private static String[] forbiddenInDomain(String basePackage) {
        return Stream.concat(
                        FORBIDDEN_IN_DOMAIN.stream(),
                        Stream.of(layer(basePackage, "api"), layer(basePackage, "infrastructure")))
                .toArray(String[]::new);
    }

    /**
     * 规则 4：api 禁止绕过 application 直接依赖 infrastructure。
     */
    public static ArchRule apiMustNotDependOnInfrastructure(String basePackage) {
        return noClasses().that().resideInAPackage(layer(basePackage, "api"))
                .should().dependOnClassesThat().resideInAPackage(layer(basePackage, "infrastructure"))
                .because("api 只能经 application 访问领域能力（设计文档 §5.2 规则 4）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 5a：禁止模块级循环依赖（{@code <base>.(*)..} 的每个直接子包为一个切片）。
     */
    public static ArchRule noModuleCycles(String basePackage) {
        return SlicesRuleDefinition.slices().matching(basePackage + ".(*)..")
                .should().beFreeOfCycles()
                .because("模块之间不得互相依赖成环（设计文档 §5.2 规则 5）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 5b：禁止包级循环依赖（每个包为一个切片）。
     */
    public static ArchRule noPackageCycles(String basePackage) {
        return SlicesRuleDefinition.slices().matching(basePackage + ".(**)..")
                .should().beFreeOfCycles()
                .because("包之间不得互相依赖成环（设计文档 §5.2 规则 5）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 6a：接口不得使用 I 前缀（{@code OrderRepository}/{@code OrderApi} 风格）。
     */
    public static ArchRule interfacesShouldNotUsePrefixI(String basePackage) {
        return classes().that().areInterfaces().and().resideInAPackage(basePackage + "..")
                .should().haveNameNotMatching(I_PREFIX_PATTERN)
                .because("仓储接口与契约接口不得使用 I 前缀（设计文档 §5.1 命名规范）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 6b：持久化对象（{@code *PO}）只允许出现在 infrastructure 层。
     */
    public static ArchRule persistenceObjectsShouldResideInInfrastructure(String basePackage) {
        return classes().that().haveSimpleNameEndingWith("PO").and().resideInAPackage(basePackage + "..")
                .should().resideInAPackage(layer(basePackage, "infrastructure"))
                .because("PO 是持久化映射对象（设计文档 §5.1 命名规范）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 6c：应用层传输对象（{@code *DTO}）只允许出现在 application 层。
     */
    public static ArchRule dataTransferObjectsShouldResideInApplication(String basePackage) {
        return classes().that().haveSimpleNameEndingWith("DTO").and().resideInAPackage(basePackage + "..")
                .should().resideInAPackage(layer(basePackage, "application"))
                .because("DTO 是应用层用例的出入参（设计文档 §5.1 命名规范）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 6d：对外传输对象（{@code *VO/*Form/*QO}）只允许出现在 contract 层。
     */
    public static ArchRule contractTransportObjectsShouldResideInContract(String basePackage) {
        return classes().that(contractTransportObject()).and().resideInAPackage(basePackage + "..")
                .should().resideInAPackage(layer(basePackage, "contract"))
                .because("VO/Form/QO 是对外契约的传输对象（设计文档 §5.1 命名规范）")
                .allowEmptyShould(true);
    }

    /**
     * 规则 7：所有 Controller 必须实现 contract 接口（含继承来的契约接口）。
     */
    public static ArchRule controllersShouldImplementContractInterfaces(String basePackage) {
        return classes().that().haveSimpleNameEndingWith("Controller").and().resideInAPackage(basePackage + "..")
                .should(implementContractInterface(basePackage))
                .because("Controller 只做协议转换，业务契约由 contract 接口定义（设计文档 §5.2 规则 7）")
                .allowEmptyShould(true);
    }

    /**
     * 设计文档 §5.2 的 7 条红线，展开为 11 条可执行规则。
     */
    public static List<ArchRule> forProject(String basePackage) {
        return List.of(
                domainPurity(basePackage),
                domainAndApplicationMustNotDependOnAdapters(basePackage),
                applicationMustNotDependOnContract(basePackage),
                apiMustNotDependOnInfrastructure(basePackage),
                noModuleCycles(basePackage),
                noPackageCycles(basePackage),
                interfacesShouldNotUsePrefixI(basePackage),
                persistenceObjectsShouldResideInInfrastructure(basePackage),
                dataTransferObjectsShouldResideInApplication(basePackage),
                contractTransportObjectsShouldResideInContract(basePackage),
                controllersShouldImplementContractInterfaces(basePackage));
    }

    private static DescribedPredicate<JavaClass> contractTransportObject() {
        return DescribedPredicate.describe(
                "对外传输对象（*VO/*Form/*QO）",
                javaClass -> endsWithAny(javaClass.getSimpleName(), "VO", "Form", "QO"));
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static ArchCondition<JavaClass> implementContractInterface(String basePackage) {
        DescribedPredicate<JavaClass> contractInterface =
                JavaClass.Predicates.resideInAPackage(layer(basePackage, "contract"));
        return new ArchCondition<>("契约接口实现") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.getAllRawInterfaces().stream().noneMatch(contractInterface)) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getName() + " 未实现 " + layer(basePackage, "contract") + " 下的契约接口"));
                }
            }
        };
    }
}
