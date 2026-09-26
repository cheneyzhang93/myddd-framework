package org.deep361.myddd.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MydddArchitectureSpecTest {

    private static final String FIXTURE = "org.deep361.myddd.archunit.fixture";
    private static final String CLEAN = FIXTURE + ".clean";
    private static final String RULE7 = FIXTURE + ".violating.rule7";

    @Test
    void verify_should_pass_on_clean_fixture() {
        JavaClasses classes = new ClassFileImporter().importPackages(CLEAN);

        assertThatCode(() -> MydddArchitectureSpec.verify(CLEAN, classes))
                .doesNotThrowAnyException();
    }

    @Test
    void verify_should_fail_on_controller_without_contract_interface() {
        JavaClasses classes = new ClassFileImporter().importPackages(RULE7);

        assertThatThrownBy(() -> MydddArchitectureSpec.verify(RULE7, classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnboundController");
    }

    /**
     * 一行入口（按包名导入）必须满足两件事：能从生产 classpath 找到类；空选择集不判失败。
     * 本模块的生产包没有分层子包，因此全部规则的选择集都为空——这正是"分层不全的工程
     * 不该构建失败"的现场证据。
     */
    @Test
    void verify_by_package_name_should_tolerate_absent_layers() {
        assertThatCode(() -> MydddArchitectureSpec.verify("org.deep361.myddd.archunit"))
                .doesNotThrowAnyException();
    }
}
