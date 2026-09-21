package org.deep361.myddd.kernel.common.util;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MydddAssertTest {

    @Test
    void should_pass_when_expression_is_true() {
        assertThatCode(() -> MydddAssert.isTrue(true, CommonErrorCode.ARGUMENT_INVALID, "x"))
                .doesNotThrowAnyException();
    }

    @Test
    void should_throw_biz_exception_when_expression_is_false() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.isTrue(false, CommonErrorCode.INVALID_PARAMETER));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER);
        assertThat(exception.getMessage()).isEqualTo("参数校验失败");
        assertThat(exception.getMessageArgs()).isEmpty();
    }

    @Test
    void should_carry_message_args_to_thrown_exception() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.isTrue(false, CommonErrorCode.ARGUMENT_INVALID, "pageSize"));

        assertThat(exception.getMessage()).isEqualTo("参数 pageSize 不合法");
        assertThat(exception.getMessageArgs()).containsExactly("pageSize");
    }

    @Test
    void should_keep_placeholder_when_parameterized_code_is_used_without_args() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.isTrue(false, CommonErrorCode.ARGUMENT_INVALID));

        assertThat(exception.getMessage()).isEqualTo("参数 {} 不合法");
        assertThat(exception.getMessageArgs()).isEmpty();
    }

    @Test
    void should_reject_null_value() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.notNull(null, CommonErrorCode.NOT_FOUND));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void should_accept_non_null_value() {
        assertThatCode(() -> MydddAssert.notNull(new Object(), CommonErrorCode.NOT_FOUND))
                .doesNotThrowAnyException();
    }

    @Test
    void should_reject_blank_string() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.notBlank("  ", CommonErrorCode.NOT_FOUND));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void should_reject_null_string() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.notBlank(null, CommonErrorCode.NOT_FOUND));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void should_accept_non_blank_string() {
        assertThatCode(() -> MydddAssert.notBlank("ok", CommonErrorCode.NOT_FOUND))
                .doesNotThrowAnyException();
    }

    @Test
    void should_reject_empty_collection() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.notEmpty(List.of(), CommonErrorCode.NOT_FOUND));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void should_reject_null_collection() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.notEmpty((List<String>) null, CommonErrorCode.NOT_FOUND));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void should_reject_empty_map() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.notEmpty(Map.of(), CommonErrorCode.NOT_FOUND));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void should_reject_null_map() {
        BizException exception = assertThrows(BizException.class,
                () -> MydddAssert.notEmpty((Map<String, String>) null, CommonErrorCode.NOT_FOUND));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void should_accept_non_empty_collection_and_map() {
        assertThatCode(() -> {
            MydddAssert.notEmpty(List.of("a"), CommonErrorCode.NOT_FOUND);
            MydddAssert.notEmpty(Map.of("k", "v"), CommonErrorCode.NOT_FOUND);
        }).doesNotThrowAnyException();
    }
}
