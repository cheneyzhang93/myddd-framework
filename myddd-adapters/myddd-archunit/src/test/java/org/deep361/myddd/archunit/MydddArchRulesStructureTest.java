package org.deep361.myddd.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MydddArchRulesStructureTest {

    private static final String FIXTURE = "org.deep361.myddd.archunit.fixture";
    private static final String CLEAN = FIXTURE + ".clean";
    private static final String RULE4 = FIXTURE + ".violating.rule4";
    private static final String RULE5 = FIXTURE + ".violating.rule5";
    private static final String RULE6 = FIXTURE + ".violating.rule6";
    private static final String RULE7 = FIXTURE + ".violating.rule7";
    private static final String RULE5_NESTED = FIXTURE + ".violating.rule5nested";
    private static final String RULE6_FORM = FIXTURE + ".violating.rule6form";

    private static JavaClasses importFixture(String fixtureRoot) {
        return new ClassFileImporter().importPackages(fixtureRoot);
    }

    @Test
    void rule4_should_pass_on_clean_fixture() {
        JavaClasses classes = importFixture(CLEAN);

        assertThatCode(() -> MydddArchRules.apiMustNotDependOnInfrastructure(CLEAN).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule4_should_fail_on_api_dependency_on_infrastructure() {
        JavaClasses classes = importFixture(RULE4);

        assertThatThrownBy(() -> MydddArchRules.apiMustNotDependOnInfrastructure(RULE4).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("InfrastructureBypassingController")
                .hasMessageContaining("设计文档 §5.2 规则 4");
    }

    @Test
    void cycles_should_pass_on_clean_fixture() {
        JavaClasses classes = importFixture(CLEAN);

        assertThatCode(() -> MydddArchRules.noModuleCycles(CLEAN).check(classes))
                .doesNotThrowAnyException();
        assertThatCode(() -> MydddArchRules.noPackageCycles(CLEAN).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void module_cycles_should_fail_on_cyclic_fixture() {
        JavaClasses classes = importFixture(RULE5);

        assertThatThrownBy(() -> MydddArchRules.noModuleCycles(RULE5).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cycle detected")
                .hasMessageContaining("modulea")
                .hasMessageContaining("moduleb")
                .hasMessageContaining("设计文档 §5.2 规则 5");
    }

    @Test
    void package_cycles_should_fail_on_cyclic_fixture() {
        JavaClasses classes = importFixture(RULE5);

        assertThatThrownBy(() -> MydddArchRules.noPackageCycles(RULE5).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cycle detected")
                .hasMessageContaining("设计文档 §5.2 规则 5");
    }

    @Test
    void nested_package_cycle_should_fail_on_package_level_rule() {
        JavaClasses classes = importFixture(RULE5_NESTED);

        assertThatThrownBy(() -> MydddArchRules.noPackageCycles(RULE5_NESTED).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cycle detected")
                .hasMessageContaining("rule5nested.mod.alpha")
                .hasMessageContaining("rule5nested.mod.beta")
                .hasMessageContaining("设计文档 §5.2 规则 5");
    }

    @Test
    void nested_package_cycle_should_pass_on_module_level_rule() {
        JavaClasses classes = importFixture(RULE5_NESTED);

        assertThatCode(() -> MydddArchRules.noModuleCycles(RULE5_NESTED).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void naming_rules_should_pass_on_clean_fixture() {
        JavaClasses classes = importFixture(CLEAN);

        assertThatCode(() -> MydddArchRules.interfacesShouldNotUsePrefixI(CLEAN).check(classes))
                .doesNotThrowAnyException();
        assertThatCode(() -> MydddArchRules.persistenceObjectsShouldResideInInfrastructure(CLEAN).check(classes))
                .doesNotThrowAnyException();
        assertThatCode(() -> MydddArchRules.dataTransferObjectsShouldResideInApplication(CLEAN).check(classes))
                .doesNotThrowAnyException();
        assertThatCode(() -> MydddArchRules.contractTransportObjectsShouldResideInContract(CLEAN).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void naming_should_fail_on_interface_with_prefix_i() {
        JavaClasses classes = importFixture(RULE6);

        assertThatThrownBy(() -> MydddArchRules.interfacesShouldNotUsePrefixI(RULE6).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("IOrderRepository")
                .hasMessageContaining("设计文档 §5.1 命名规范");
    }

    @Test
    void naming_should_fail_on_po_outside_infrastructure() {
        JavaClasses classes = importFixture(RULE6);

        assertThatThrownBy(() -> MydddArchRules.persistenceObjectsShouldResideInInfrastructure(RULE6).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("OrderPO")
                .hasMessageContaining("设计文档 §5.1 命名规范");
    }

    @Test
    void naming_should_fail_on_dto_outside_application() {
        JavaClasses classes = importFixture(RULE6);

        assertThatThrownBy(() -> MydddArchRules.dataTransferObjectsShouldResideInApplication(RULE6).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("OrderDTO")
                .hasMessageContaining("设计文档 §5.1 命名规范");
    }

    @Test
    void naming_should_fail_on_contract_transport_object_outside_contract() {
        JavaClasses classes = importFixture(RULE6);

        assertThatThrownBy(() -> MydddArchRules.contractTransportObjectsShouldResideInContract(RULE6).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("OrderVO")
                .hasMessageContaining("OrderQO")
                .hasMessageContaining("设计文档 §5.1 命名规范");
    }

    @Test
    void naming_should_fail_on_form_outside_contract() {
        JavaClasses classes = importFixture(RULE6_FORM);

        assertThatThrownBy(() -> MydddArchRules.contractTransportObjectsShouldResideInContract(RULE6_FORM).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("OrderForm")
                .hasMessageContaining("设计文档 §5.1 命名规范");
    }

    @Test
    void naming_should_allow_io_prefixed_interface() {
        String allowed = RULE6 + ".allowed";
        JavaClasses classes = importFixture(allowed);

        assertThatCode(() -> MydddArchRules.interfacesShouldNotUsePrefixI(allowed).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule7_should_pass_on_clean_fixture() {
        JavaClasses classes = importFixture(CLEAN);

        assertThatCode(() -> MydddArchRules.controllersShouldImplementContractInterfaces(CLEAN).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule7_should_fail_on_controller_without_contract_interface() {
        JavaClasses classes = importFixture(RULE7);

        assertThatThrownBy(() -> MydddArchRules.controllersShouldImplementContractInterfaces(RULE7).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnboundController")
                .hasMessageContaining("设计文档 §5.2 规则 7");
    }

    @Test
    void for_project_should_expose_eleven_rules() {
        assertThat(MydddArchRules.forProject(CLEAN)).hasSize(11);
    }
}
