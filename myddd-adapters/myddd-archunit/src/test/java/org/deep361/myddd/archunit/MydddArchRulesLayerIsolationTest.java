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
    private static final String FAMILY = FIXTURE + ".violating.family";
    private static final String DEPTH = FIXTURE + ".depth";
    private static final String API_ONLY = FIXTURE + ".layout.apionly";

    private static final String REASON1 = "设计文档 §5.2 规则 1";
    private static final String REASON2 = "设计文档 §5.2 规则 2";
    private static final String REASON3 = "设计文档 §5.2 规则 3";

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
    void clean_fixture_must_not_be_an_empty_selection() {
        JavaClasses classes = importFixture(CLEAN);

        assertThatCode(() -> MydddArchRules.domainPurity(CLEAN).allowEmptyShould(false).check(classes))
                .doesNotThrowAnyException();
        assertThatCode(() -> MydddArchRules.domainAndApplicationMustNotDependOnAdapters(CLEAN)
                .allowEmptyShould(false).check(classes))
                .doesNotThrowAnyException();
        assertThatCode(() -> MydddArchRules.applicationMustNotDependOnContract(CLEAN)
                .allowEmptyShould(false).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule1_should_fail_on_spring_in_domain() {
        JavaClasses classes = importFixture(RULE1);

        assertThatThrownBy(() -> MydddArchRules.domainPurity(RULE1).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SpringPollutedDomainService")
                .hasMessageContaining("org.springframework.stereotype.Component")
                .hasMessageContaining(REASON1);
    }

    @Test
    void rule1_should_fail_on_adapter_in_domain() {
        JavaClasses classes = importFixture(RULE1);

        assertThatThrownBy(() -> MydddArchRules.domainPurity(RULE1).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AdapterLeakingDomainService");
    }

    @Test
    void rule1_should_fail_on_mybatis_plus_in_domain() {
        assertRule1FailsOnFamily("mybatis", "MybatisPollutedDomainService",
                "com.baomidou.mybatisplus.annotation.TableName");
    }

    @Test
    void rule1_should_fail_on_mybatis_in_domain() {
        assertRule1FailsOnFamily("ibatis", "IbatisPollutedDomainService",
                "org.apache.ibatis.type.Alias");
    }

    @Test
    void rule1_should_fail_on_jackson2_in_domain() {
        assertRule1FailsOnFamily("jackson2", "Jackson2PollutedDomainService",
                "com.fasterxml.jackson.annotation.JsonProperty");
    }

    @Test
    void rule1_should_fail_on_jackson3_in_domain() {
        assertRule1FailsOnFamily("jackson3", "Jackson3PollutedDomainService",
                "tools.jackson.databind.ObjectMapper");
    }

    @Test
    void rule1_should_fail_on_jpa_in_domain() {
        assertRule1FailsOnFamily("jpa", "JpaPollutedDomainService",
                "jakarta.persistence.Entity");
    }

    @Test
    void rule1_should_fail_on_jta_in_domain() {
        assertRule1FailsOnFamily("jta", "JtaPollutedDomainService",
                "jakarta.transaction.Transactional");
    }

    private static void assertRule1FailsOnFamily(String family, String pollutedClass, String forbiddenType) {
        String root = FAMILY + "." + family;
        JavaClasses classes = importFixture(root);

        assertThatThrownBy(() -> MydddArchRules.domainPurity(root).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(pollutedClass)
                .hasMessageContaining(forbiddenType);
    }

    @Test
    void rule1_should_not_fail_when_no_domain_package_exists() {
        JavaClasses classes = importFixture(RULE3);

        assertThatCode(() -> MydddArchRules.domainPurity(RULE3).check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void rule1_should_fail_on_spring_in_domain_sub_package() {
        String root = DEPTH + ".rule1a";
        JavaClasses classes = importFixture(root);

        assertThatThrownBy(() -> MydddArchRules.domainPurity(root).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DeepSpringDomainService")
                .hasMessageContaining("org.springframework.stereotype.Component");
    }

    @Test
    void rule1_should_fail_on_deep_adapter_dependency_from_domain() {
        String root = DEPTH + ".rule1a";
        JavaClasses classes = importFixture(root);

        assertThatThrownBy(() -> MydddArchRules.domainPurity(root).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DirectAdapterDomainService")
                .hasMessageContaining("DeepAdapter");
    }

    @Test
    void rule1_should_fail_on_spring_in_intermediate_package() {
        String root = DEPTH + ".rule1b";
        JavaClasses classes = importFixture(root);

        assertThatThrownBy(() -> MydddArchRules.domainPurity(root).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("InnerSpringDomainService");
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
                .hasMessageContaining("AdapterPollutedDomainService")
                .hasMessageContaining(REASON2);
    }

    @Test
    void rule2_should_fail_on_api_dependency_from_application() {
        JavaClasses classes = importFixture(RULE2);

        assertThatThrownBy(() -> MydddArchRules.domainAndApplicationMustNotDependOnAdapters(RULE2).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("AdapterPollutedAppService");
    }

    @Test
    void rule2_should_fail_on_deep_adapter_dependency() {
        String root = DEPTH + ".rule2";
        JavaClasses classes = importFixture(root);

        assertThatThrownBy(() -> MydddArchRules.domainAndApplicationMustNotDependOnAdapters(root).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DeepPollutedDomainService")
                .hasMessageContaining("DeepAdapter");
    }

    @Test
    void rule2_should_not_fail_when_no_inner_layer_package_exists() {
        JavaClasses classes = importFixture(API_ONLY);

        assertThatCode(() -> MydddArchRules.domainAndApplicationMustNotDependOnAdapters(API_ONLY).check(classes))
                .doesNotThrowAnyException();
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
                .hasMessageContaining("ContractPollutedAppService")
                .hasMessageContaining(REASON3);
    }

    @Test
    void rule3_should_fail_on_deep_contract_dependency() {
        String root = DEPTH + ".rule3";
        JavaClasses classes = importFixture(root);

        assertThatThrownBy(() -> MydddArchRules.applicationMustNotDependOnContract(root).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DeepPollutedAppService")
                .hasMessageContaining("DeepContractApi");
    }

    @Test
    void rule3_should_not_fail_when_no_application_package_exists() {
        JavaClasses classes = importFixture(API_ONLY);

        assertThatCode(() -> MydddArchRules.applicationMustNotDependOnContract(API_ONLY).check(classes))
                .doesNotThrowAnyException();
    }
}
