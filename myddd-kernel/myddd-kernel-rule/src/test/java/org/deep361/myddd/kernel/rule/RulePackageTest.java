package org.deep361.myddd.kernel.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RulePackageTest {

    @Test
    void should_execute_first_matching_rule_only() {
        RulePackage<BigDecimal> rulePackage = RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.vipDiscountRule())
                .rule(RuleFixtures.normalDiscountRule())
                .rule(RuleFixtures.explosiveRule("never-matched"))
                .build();

        assertThat(rulePackage.apply(RuleFixtures.vipMatch())).contains(new BigDecimal("80.0000"));
    }

    @Test
    void should_fall_through_to_next_rule_when_first_does_not_match() {
        RulePackage<BigDecimal> rulePackage = RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.vipDiscountRule())
                .rule(RuleFixtures.normalDiscountRule())
                .build();

        assertThat(rulePackage.apply(RuleFixtures.normalMatch())).contains(new BigDecimal("95.0000"));
    }

    @Test
    void should_return_empty_when_no_rule_matches() {
        RulePackage<BigDecimal> rulePackage = RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.vipDiscountRule())
                .build();

        assertThat(rulePackage.apply(RuleFixtures.normalMatch())).isEmpty();
        assertThat(rulePackage.firstMatch(RuleFixtures.normalMatch())).isEmpty();
        assertThat(rulePackage.matching(RuleFixtures.normalMatch())).isEmpty();
    }

    @Test
    void should_report_matching_rules_in_registration_order() {
        RulePackage<BigDecimal> rulePackage = RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.vipDiscountRule())
                .rule(RuleFixtures.normalDiscountRule())
                .build();

        assertThat(rulePackage.matching(RuleFixtures.normalMatch()))
                .extracting(Rule::name)
                .containsExactly("normal-discount");
        assertThat(rulePackage.matching(RuleFixtures.vipMatch()))
                .extracting(Rule::name)
                .containsExactly("vip-discount", "normal-discount");
        assertThat(rulePackage.firstMatch(RuleFixtures.vipMatch())).map(Rule::name).contains("vip-discount");
    }

    @Test
    void should_expose_name_rules_and_size() {
        RulePackage<BigDecimal> rulePackage = RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.vipDiscountRule())
                .rule(RuleFixtures.normalDiscountRule())
                .build();

        assertThat(rulePackage.name()).isEqualTo("discount");
        assertThat(rulePackage.size()).isEqualTo(2);
        assertThat(rulePackage.rules()).extracting(Rule::name).containsExactly("vip-discount", "normal-discount");
        assertThatThrownBy(() -> rulePackage.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_reject_invalid_package_definition() {
        assertThatThrownBy(() -> RulePackage.builder(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RulePackage.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RulePackage.<BigDecimal>builder("discount").rule(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RulePackage.<BigDecimal>builder("discount").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少");
    }

    @Test
    void should_reject_duplicate_rule_names() {
        assertThatThrownBy(() -> RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.vipDiscountRule())
                .rule(RuleFixtures.vipDiscountRule()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vip-discount");
    }

    @Test
    void should_reject_rule_with_blank_name() {
        assertThatThrownBy(() -> RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.explosiveRule(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void should_reject_null_match() {
        RulePackage<BigDecimal> rulePackage = RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.normalDiscountRule())
                .build();

        assertThatThrownBy(() -> rulePackage.apply(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> rulePackage.matching(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> rulePackage.firstMatch(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_propagate_rule_failure() {
        Rule<BigDecimal> failing = new Rule<>() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public boolean matches(RuleMatch match) {
                throw new IllegalStateException("match boom");
            }

            @Override
            public BigDecimal apply(RuleMatch match) {
                return BigDecimal.ONE;
            }
        };
        RulePackage<BigDecimal> rulePackage = RulePackage.<BigDecimal>builder("discount").rule(failing).build();

        assertThatThrownBy(() -> rulePackage.apply(RuleFixtures.normalMatch()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("match boom");
    }
}
