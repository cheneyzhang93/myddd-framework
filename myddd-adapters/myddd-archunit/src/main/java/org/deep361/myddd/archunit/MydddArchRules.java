package org.deep361.myddd.archunit;

import com.tngtech.archunit.lang.ArchRule;

import java.util.List;
import java.util.stream.Stream;

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
}
