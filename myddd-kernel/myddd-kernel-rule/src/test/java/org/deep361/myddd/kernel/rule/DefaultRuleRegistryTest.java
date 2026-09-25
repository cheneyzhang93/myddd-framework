package org.deep361.myddd.kernel.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRuleRegistryTest {

    private static RulePackage<BigDecimal> discountPackage() {
        return RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.vipDiscountRule())
                .rule(RuleFixtures.normalDiscountRule())
                .build();
    }

    private static RulePackage<Boolean> riskPackage() {
        return RulePackage.<Boolean>builder("risk").rule(new Rule<>() {
            @Override
            public String name() {
                return "always-block";
            }

            @Override
            public boolean matches(RuleMatch match) {
                return true;
            }

            @Override
            public Boolean apply(RuleMatch match) {
                return Boolean.TRUE;
            }
        }).build();
    }

    @Test
    void should_register_and_lookup_package_by_name() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder().register(discountPackage()).build();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.packageNames()).containsExactly("discount");
        assertThat(registry.<BigDecimal>get("discount").name()).isEqualTo("discount");
        assertThat(registry.<BigDecimal>find("discount")).isPresent();
    }

    @Test
    void should_apply_rule_of_named_package() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder()
                .register(discountPackage())
                .register(riskPackage())
                .build();

        assertThat(registry.apply("discount", RuleFixtures.vipMatch())).contains(new BigDecimal("80.0000"));
        assertThat(registry.apply("discount", RuleFixtures.normalMatch())).contains(new BigDecimal("95.0000"));
        assertThat(registry.<Boolean>apply("risk", RuleFixtures.normalMatch())).contains(Boolean.TRUE);
    }

    @Test
    void should_return_empty_when_package_not_found() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder().register(discountPackage()).build();

        assertThat(registry.find("unknown")).isEmpty();
        assertThat(registry.<BigDecimal>apply("unknown", RuleFixtures.vipMatch())).isEmpty();
    }

    @Test
    void should_fail_get_with_registered_package_names() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder().register(discountPackage()).build();

        assertThatThrownBy(() -> registry.get("unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("unknown")
                .hasMessageContaining("[discount]");
    }

    @Test
    void should_reject_duplicate_package_name() {
        assertThatThrownBy(() -> DefaultRuleRegistry.builder()
                .register(discountPackage())
                .register(discountPackage()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discount");
    }

    @Test
    void should_reject_null_package_and_null_match_argument() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder().register(discountPackage()).build();

        assertThatThrownBy(() -> DefaultRuleRegistry.builder().register(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.apply(null, RuleFixtures.vipMatch()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.apply("discount", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_expose_immutable_package_names() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder()
                .register(discountPackage())
                .register(riskPackage())
                .build();

        assertThat(registry.packageNames()).containsExactly("discount", "risk");
        assertThatThrownBy(() -> registry.packageNames().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_be_empty_when_nothing_registered() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder().build();

        assertThat(registry.size()).isZero();
        assertThat(registry.packageNames()).isEmpty();
    }
}
