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
                .hasMessage("未注册的规则包：unknown，已注册规则包=[discount]");
    }

    @Test
    void should_reject_duplicate_package_name() {
        assertThatThrownBy(() -> DefaultRuleRegistry.builder()
                .register(discountPackage())
                .register(discountPackage()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("规则包名重复：discount");
    }

    @Test
    void should_reject_null_package_and_null_match_argument() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder().register(discountPackage()).build();

        assertThatThrownBy(() -> DefaultRuleRegistry.builder().register(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("规则包不能为 null");
        assertThatThrownBy(() -> registry.apply(null, RuleFixtures.vipMatch()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("规则包名不能为 null");
        assertThatThrownBy(() -> registry.apply("discount", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("match 不能为 null");
        assertThatThrownBy(() -> registry.apply("unknown", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("match 不能为 null");
        assertThatThrownBy(() -> registry.get(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("规则包名不能为 null");
        assertThatThrownBy(() -> registry.find(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("规则包名不能为 null");
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

    @Test
    void should_return_empty_when_package_has_no_matching_rule() {
        DefaultRuleRegistry vipOnly = DefaultRuleRegistry.builder()
                .register(RulePackage.<BigDecimal>builder("discount")
                        .rule(RuleFixtures.vipDiscountRule())
                        .build())
                .build();

        assertThat(vipOnly.<BigDecimal>find("discount")).isPresent();
        assertThat(vipOnly.apply("discount", RuleFixtures.normalMatch())).isEmpty();
    }

    @Test
    void should_match_package_name_exactly() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder().register(discountPackage()).build();

        assertThat(registry.find("Discount")).isEmpty();
        assertThatThrownBy(() -> registry.get("DISCOUNT")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> registry.get(" discount ")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void should_snapshot_packages_at_build_time() {
        DefaultRuleRegistry.Builder builder = DefaultRuleRegistry.builder().register(discountPackage());
        DefaultRuleRegistry registry = builder.build();

        builder.register(riskPackage());

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.packageNames()).containsExactly("discount");
        assertThat(builder.build().size()).isEqualTo(2);
    }

    @Test
    void should_report_package_names_in_registration_order() {
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder()
                .register(riskPackage())
                .register(discountPackage())
                .build();

        assertThat(registry.packageNames()).containsExactly("risk", "discount");
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void should_keep_first_package_when_duplicate_name_registered() {
        RulePackage<BigDecimal> first = discountPackage();
        RulePackage<BigDecimal> second = RulePackage.<BigDecimal>builder("discount")
                .rule(RuleFixtures.normalDiscountRule())
                .build();
        DefaultRuleRegistry.Builder builder = DefaultRuleRegistry.builder().register(first);

        assertThatThrownBy(() -> builder.register(second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("规则包名重复：discount");

        // 留存断言必须先于"同一实例再注册"：后者会（在覆盖写实现下）把 map 修回 first，从而掩盖覆盖写
        DefaultRuleRegistry registry = builder.build();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.<BigDecimal>get("discount")).isSameAs(first);

        assertThatThrownBy(() -> builder.register(first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("规则包名重复：discount");
        assertThat(builder.build().<BigDecimal>get("discount")).isSameAs(first);
        assertThat(builder.build().size()).isEqualTo(1);
    }

    @Test
    void should_not_swallow_rule_contract_violation() {
        Rule<BigDecimal> nullResult = new Rule<>() {
            @Override
            public String name() {
                return "null-result";
            }

            @Override
            public boolean matches(RuleMatch match) {
                return true;
            }

            @Override
            public BigDecimal apply(RuleMatch match) {
                return null;
            }
        };
        DefaultRuleRegistry registry = DefaultRuleRegistry.builder()
                .register(RulePackage.<BigDecimal>builder("discount").rule(nullResult).build())
                .build();

        assertThatThrownBy(() -> registry.apply("discount", RuleFixtures.vipMatch()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("规则 null-result 命中后 apply 返回 null：命中规则必须产出结果，否则应在 matches 中判否");
    }
}
