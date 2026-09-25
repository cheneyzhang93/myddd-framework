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
        assertThatThrownBy(() -> RuleMatch.of("discount").attributes().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_report_attribute_presence() {
        RuleMatch match = RuleFixtures.vipMatch();

        assertThat(match.hasAttribute(RuleFixtures.LEVEL)).isTrue();
        assertThat(match.hasAttribute("missing")).isFalse();
    }

    @Test
    void should_keep_insertion_order_of_attributes() {
        RuleMatch match = RuleMatch.of("discount").with("z", 1).with("y", 2).with("x", 3);

        assertThat(match.attributes().keySet()).containsExactly("z", "y", "x");
        assertThat(match.attributes().values()).containsExactly(1, 2, 3);
    }

    @Test
    void should_require_attribute_with_type() {
        RuleMatch match = RuleFixtures.vipMatch();

        assertThat(match.requireAttribute(RuleFixtures.AMOUNT, BigDecimal.class)).isEqualTo(new BigDecimal("100.00"));
        assertThatThrownBy(() -> match.requireAttribute("missing", BigDecimal.class))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("规则事实缺少属性：missing，可用属性=[level, amount]");
        assertThatThrownBy(() -> match.requireAttribute(RuleFixtures.AMOUNT, String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("规则事实属性类型不符：amount 期望 String，实际 java.math.BigDecimal");
    }

    @Test
    void should_reject_null_scenario_key_and_value() {
        assertThatThrownBy(() -> RuleMatch.of(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("scenario 不能为 null");
        assertThatThrownBy(() -> RuleMatch.of(" ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario 不能为空白");
        assertThatThrownBy(() -> RuleMatch.of("discount").with(null, "x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("属性名不能为 null");
        assertThatThrownBy(() -> RuleMatch.of("discount").with("k", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("属性值不能为 null：k");
    }

    @Test
    void should_reject_null_key_on_readers() {
        RuleMatch match = RuleFixtures.vipMatch();

        assertThatThrownBy(() -> match.hasAttribute(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("属性名不能为 null");
        assertThatThrownBy(() -> match.attribute(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("属性名不能为 null");
        assertThatThrownBy(() -> match.requireAttribute(null, BigDecimal.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("属性名不能为 null");
        assertThatThrownBy(() -> match.requireAttribute(RuleFixtures.LEVEL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("type 不能为 null");
    }
}
