package org.deep361.myddd.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MydddArchRulesLayerIsolationTest {

    private static final String FIXTURE = "org.deep361.myddd.archunit.fixture";
    private static final String CLEAN = FIXTURE + ".clean";
    private static final String RULE1 = FIXTURE + ".violating.rule1";
    private static final String RULE2 = FIXTURE + ".violating.rule2";
    private static final String RULE3 = FIXTURE + ".violating.rule3";

    private static JavaClasses importFixture(String fixtureRoot) {
        return new ClassFileImporter().importPackages(fixtureRoot);
    }

    @Test
    void rule1_should_pass_on_clean_fixture() {
        JavaClasses classes = importFixture(CLEAN);

        assertThatCode(() -> MydddArchRules.domainPurity(CLEAN).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule1_should_fail_on_spring_in_domain() {
        JavaClasses classes = importFixture(RULE1);

        assertThatThrownBy(() -> MydddArchRules.domainPurity(RULE1).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SpringPollutedDomainService")
                .hasMessageContaining("org.springframework.stereotype.Component");
    }

    @Test
    void rule1_should_fail_on_adapter_in_domain() {
        JavaClasses classes = importFixture(RULE1);

        assertThatThrownBy(() -> MydddArchRules.domainPurity(RULE1).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AdapterLeakingDomainService");
    }

    @Test
    void rule1_should_not_fail_when_no_domain_package_exists() {
        JavaClasses classes = importFixture(RULE3);

        assertThatCode(() -> MydddArchRules.domainPurity(RULE3).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule2_should_pass_on_clean_fixture() {
        JavaClasses classes = importFixture(CLEAN);

        assertThatCode(() -> MydddArchRules.domainAndApplicationMustNotDependOnAdapters(CLEAN).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule2_should_fail_on_infrastructure_dependency_from_domain() {
        JavaClasses classes = importFixture(RULE2);

        assertThatThrownBy(() -> MydddArchRules.domainAndApplicationMustNotDependOnAdapters(RULE2).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AdapterPollutedDomainService");
    }

    @Test
    void rule2_should_fail_on_api_dependency_from_application() {
        JavaClasses classes = importFixture(RULE2);

        assertThatThrownBy(() -> MydddArchRules.domainAndApplicationMustNotDependOnAdapters(RULE2).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AdapterPollutedAppService");
    }

    @Test
    void rule3_should_pass_on_clean_fixture() {
        JavaClasses classes = importFixture(CLEAN);

        assertThatCode(() -> MydddArchRules.applicationMustNotDependOnContract(CLEAN).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule3_should_fail_on_contract_dependency_from_application() {
        JavaClasses classes = importFixture(RULE3);

        assertThatThrownBy(() -> MydddArchRules.applicationMustNotDependOnContract(RULE3).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ContractPollutedAppService");
    }
}
