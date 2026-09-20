package org.deep361.myddd.kernel.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFormatterTest {

    @Test
    void should_format_single_placeholder() {
        assertThat(MessageFormatter.format("订单 {} 不存在", "A001")).isEqualTo("订单 A001 不存在");
    }

    @Test
    void should_format_multiple_placeholders_in_order() {
        assertThat(MessageFormatter.format("{} 在 {} 失败", "支付", "渠道A")).isEqualTo("支付 在 渠道A 失败");
    }

    @Test
    void should_keep_placeholder_when_arguments_are_fewer() {
        assertThat(MessageFormatter.format("{} 与 {}", "A")).isEqualTo("A 与 {}");
    }

    @Test
    void should_ignore_extra_arguments() {
        assertThat(MessageFormatter.format("{}", "A", "B")).isEqualTo("A");
    }

    @Test
    void should_render_null_argument_as_literal_null() {
        assertThat(MessageFormatter.format("值={}", (Object) null)).isEqualTo("值=null");
    }

    @Test
    void should_return_template_when_no_arguments() {
        assertThat(MessageFormatter.format("无占位符 {x}")).isEqualTo("无占位符 {x}");
    }

    @Test
    void should_return_null_for_null_template() {
        assertThat(MessageFormatter.format(null, "A")).isNull();
    }

    @Test
    void should_not_rescan_arguments_for_placeholders() {
        assertThat(MessageFormatter.format("a{}b", "{}")).isEqualTo("a{}b");
    }

    @Test
    void should_format_consecutive_placeholders() {
        assertThat(MessageFormatter.format("{}{}", "a", "b")).isEqualTo("ab");
    }

    @Test
    void should_leave_opposite_braces_untouched() {
        assertThat(MessageFormatter.format("a}{b", "x")).isEqualTo("a}{b");
    }

    @Test
    void should_return_template_when_argument_array_is_null() {
        assertThat(MessageFormatter.format("值={}", (Object[]) null)).isEqualTo("值={}");
    }
}
