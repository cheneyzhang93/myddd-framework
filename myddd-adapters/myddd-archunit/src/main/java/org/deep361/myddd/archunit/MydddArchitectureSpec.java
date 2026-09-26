package org.deep361.myddd.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 架构守卫入口：业务工程一行接入。
 *
 * <pre>{@code
 * @Test
 * void architecture() {
 *     MydddArchitectureSpec.verify("com.deep361.order");
 * }
 * }</pre>
 *
 * <p>{@link #verify(String)} 只分析生产代码（排除测试类）：测试替身与切片测试常带框架依赖，
 * 卷进"领域纯净红线"会误报。需要自定义导入策略（例如额外排除夹具包）时用
 * {@link #verify(String, JavaClasses)}。
 */
public final class MydddArchitectureSpec {

    private MydddArchitectureSpec() {
    }

    /** 导入 basePackage 下的生产代码并执行全部守卫规则；有违规抛 {@link AssertionError}。 */
    public static void verify(String basePackage) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(basePackage);
        verify(basePackage, classes);
    }

    /** 对已导入的类集执行全部守卫规则。 */
    public static void verify(String basePackage, JavaClasses classes) {
        for (ArchRule rule : MydddArchRules.forProject(basePackage)) {
            rule.check(classes);
        }
    }
}
