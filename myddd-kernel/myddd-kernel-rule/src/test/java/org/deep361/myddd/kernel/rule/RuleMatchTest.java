package org.deep361.myddd.kernel.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleMatchTest {

    @Test
    void should_start_with_scenario_and_no_attributes() {
        RuleMatch match = RuleMatch.of("discount");

        assertThat(match.scenario()).isEqualTo("discount");
        assertThat(match.size()).isZero();
        assertThat(match.attributes()).isEmpty();
        assertThat(match.hasAttribute(RuleFixtures.LEVEL)).isFalse();
        assertThat(match.attribute(RuleFixtures.LEVEL)).isEmpty();
    }

    @Test
    void should_add_attribute_returning_new_instance() {
        RuleMatch base = RuleMatch.of("discount");
        RuleMatch enriched = base.with(RuleFixtures.LEVEL, RuleFixtures.MemberLevel.VIP);

        assertThat(enriched.size()).isEqualTo(1);
        assertThat(base.size()).isZero(); // 原实例不变：事实表不可变，可安全跨规则共享
        assertThat(enriched.attribute(RuleFixtures.LEVEL)).contains(RuleFixtures.MemberLevel.VIP);
    }

    @Test
    void should_override_existing_attribute() {
        RuleMatch match = RuleMatch.of("discount")
                .with(RuleFixtures.LEVEL, RuleFixtures.MemberLevel.NORMAL)
                .with(RuleFixtures.LEVEL, RuleFixtures.MemberLevel.VIP);

        assertThat(match.size()).isEqualTo(1);
        assertThat(match.attribute(RuleFixtures.LEVEL)).contains(RuleFixtures.MemberLevel.VIP);
    }

    @Test
    void should_expose_immutable_attributes() {
        RuleMatch match = RuleFixtures.vipMatch();

        assertThatThrownBy(() -> match.attributes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_require_attribute_with_type() {
        RuleMatch match = RuleFixtures.vipMatch();

        assertThat(match.requireAttribute(RuleFixtures.AMOUNT, BigDecimal.class)).isEqualTo(new BigDecimal("100.00"));
        assertThatThrownBy(() -> match.requireAttribute("missing", BigDecimal.class))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("missing")
                .hasMessageContaining("可用属性");
        assertThatThrownBy(() -> match.requireAttribute(RuleFixtures.AMOUNT, String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount")
                .hasMessageContaining("BigDecimal");
    }

    @Test
    void should_reject_null_scenario_key_and_value() {
        assertThatThrownBy(() -> RuleMatch.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RuleMatch.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleMatch.of("discount").with(null, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RuleMatch.of("discount").with("k", null))
                .isInstanceOf(NullPointerException.class);
    }
}
