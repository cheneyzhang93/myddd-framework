package org.deep361.myddd.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.deep361.myddd.kernel.ddd.model.AbstractValueObject;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

class MydddKernelPurityTest {

    private static JavaClasses importKernel(String kernelPackage) {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(kernelPackage);
    }

    private static void assertKernelDependsOnlyOn(String kernelPackage, String... allowedPackages) {
        JavaClasses classes = importKernel(kernelPackage);

        classes().that().resideInAPackage(kernelPackage + "..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(allowedPackages)
                .because("内核模块必须零框架依赖（设计文档 §0.3 硬性约束 3）")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void kernel_common_should_depend_only_on_jdk() {
        assertKernelDependsOnlyOn("org.deep361.myddd.kernel.common",
                "java..", "org.deep361.myddd.kernel.common..");
    }

    @Test
    void kernel_ddd_should_depend_only_on_jdk_and_kernel_common() {
        assertKernelDependsOnlyOn("org.deep361.myddd.kernel.ddd",
                "java..", "org.deep361.myddd.kernel.common..", "org.deep361.myddd.kernel.ddd..");
    }

    @Test
    void kernel_port_should_be_dependency_free() {
        assertKernelDependsOnlyOn("org.deep361.myddd.kernel.port",
                "java..", "org.deep361.myddd.kernel.port..");
    }

    @Test
    void kernel_extension_should_depend_only_on_jdk_and_kernel_common() {
        assertKernelDependsOnlyOn("org.deep361.myddd.kernel.extension",
                "java..", "org.deep361.myddd.kernel.common..", "org.deep361.myddd.kernel.extension..");
    }

    @Test
    void kernel_statemachine_should_depend_only_on_jdk_and_kernel_common() {
        assertKernelDependsOnlyOn("org.deep361.myddd.kernel.statemachine",
                "java..", "org.deep361.myddd.kernel.common..", "org.deep361.myddd.kernel.statemachine..");
    }

    @Test
    void kernel_rule_should_depend_only_on_jdk_and_kernel_common() {
        assertKernelDependsOnlyOn("org.deep361.myddd.kernel.rule",
                "java..", "org.deep361.myddd.kernel.common..", "org.deep361.myddd.kernel.rule..");
    }

    @Test
    void kernel_module_cycles_should_be_absent() {
        JavaClasses classes = importKernel("org.deep361.myddd.kernel");

        MydddArchRules.noModuleCycles("org.deep361.myddd.kernel").check(classes);
    }

    @Test
    void kernel_package_cycles_should_be_absent() {
        JavaClasses classes = importKernel("org.deep361.myddd.kernel");

        MydddArchRules.noPackageCycles("org.deep361.myddd.kernel").check(classes);
    }

    @Test
    void framework_module_cycles_should_be_absent() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("org.deep361.myddd");

        MydddArchRules.noModuleCycles("org.deep361.myddd").check(classes);
    }

    @Test
    void abstract_value_object_should_declare_no_instance_field() {
        JavaClass valueObject = new ClassFileImporter().importClass(AbstractValueObject.class);

        assertThat(valueObject.getFields())
                .as("AbstractValueObject 的 equals/hashCode 只扫描子类字段，基类若有实例字段会被静默忽略")
                .noneMatch(field -> !field.getModifiers().contains(JavaModifier.STATIC));
    }
}
