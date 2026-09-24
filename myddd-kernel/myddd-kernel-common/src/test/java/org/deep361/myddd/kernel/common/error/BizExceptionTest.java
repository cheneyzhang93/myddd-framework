package org.deep361.myddd.kernel.common.error;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BizExceptionTest {

    @Test
    void should_carry_error_code_and_default_message() {
        BizException exception = new BizException(CommonErrorCode.NOT_FOUND);

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("资源不存在");
        assertThat(exception.getMessageArgs()).isEmpty();
    }

    @Test
    void should_format_message_with_args() {
        BizException exception = new BizException(CommonErrorCode.ARGUMENT_INVALID, "pageSize");

        assertThat(exception.getMessage()).isEqualTo("参数 pageSize 不合法");
        assertThat(exception.getMessageArgs()).containsExactly("pageSize");
    }

    @Test
    void should_keep_cause_via_factory() {
        IllegalStateException cause = new IllegalStateException("boom");

        BizException exception = BizException.causedBy(CommonErrorCode.SYSTEM_ERROR, cause, "订单处理");

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.SYSTEM_ERROR);
    }

    @Test
    void should_reject_null_error_code_from_both_entry_points() {
        assertThatThrownBy(() -> new BizException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errorCode 不能为 null");

        assertThatThrownBy(() -> BizException.causedBy(null, new IllegalStateException("boom")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errorCode 不能为 null");
    }

    @Test
    void should_defensively_copy_message_args() {
        Object[] args = {"A"};

        BizException exception = new BizException(CommonErrorCode.ARGUMENT_INVALID, args);
        args[0] = "B";

        assertThat(exception.getMessageArgs()).containsExactly("A");
    }

    @Test
    void should_render_template_verbatim_when_parameterized_code_has_no_args() {
        BizException exception = new BizException(CommonErrorCode.ARGUMENT_INVALID);

        assertThat(exception.getMessage()).isEqualTo("参数 {} 不合法");
        assertThat(exception.getMessageArgs()).isEmpty();
    }

    @Test
    void should_treat_explicit_null_args_as_empty() {
        BizException exception = new BizException(CommonErrorCode.ARGUMENT_INVALID, (Object[]) null);

        assertThat(exception.getMessage()).isEqualTo("参数 {} 不合法");
        assertThat(exception.getMessageArgs()).isNotNull().isEmpty();
    }

    @Test
    void should_treat_bare_null_as_no_args() {
        // 文档化的 varargs 陷阱：bare null 是"传 null 数组"即无参数（javac 会给出 non-varargs 调用警告，
        // 此处刻意保留该写法，使 javadoc 的声明可被测试执行）；渲染一个 null 参数须写 (Object) null。
        BizException exception = new BizException(CommonErrorCode.ARGUMENT_INVALID, null);

        assertThat(exception.getMessage()).isEqualTo("参数 {} 不合法");
        assertThat(exception.getMessageArgs()).isEmpty();
    }

    @Test
    void should_defensively_copy_args_on_getter() {
        BizException exception = new BizException(CommonErrorCode.ARGUMENT_INVALID, "A");

        Object[] returned = exception.getMessageArgs();
        returned[0] = "B";

        assertThat(exception.getMessageArgs()).containsExactly("A");
    }

    @Test
    void should_render_message_and_args_on_causedBy_path() {
        IllegalStateException cause = new IllegalStateException("boom");

        BizException exception = BizException.causedBy(CommonErrorCode.ARGUMENT_INVALID, cause, "pageSize");

        assertThat(exception.getMessage()).isEqualTo("参数 pageSize 不合法");
        assertThat(exception.getMessageArgs()).containsExactly("pageSize");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void should_reject_throwable_message_argument() {
        IllegalStateException throwable = new IllegalStateException("boom");

        assertThatThrownBy(() -> new BizException(CommonErrorCode.ARGUMENT_INVALID, throwable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("causedBy");
    }

    static class StubDomainException extends BizException {

        StubDomainException(Throwable cause, Object... messageArgs) {
            super(CommonErrorCode.ARGUMENT_INVALID, cause, messageArgs);
        }
    }

    @Test
    void should_allow_subclass_to_carry_cause() {
        IllegalStateException cause = new IllegalStateException("boom");

        StubDomainException exception = new StubDomainException(cause, "pageSize");

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getMessage()).isEqualTo("参数 pageSize 不合法");
        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.ARGUMENT_INVALID);
    }

    @Test
    void should_survive_serialization_round_trip() throws Exception {
        BizException exception = new BizException(CommonErrorCode.ARGUMENT_INVALID, "pageSize");

        BizException restored = (BizException) roundTrip(exception);

        assertThat(restored.getErrorCode()).isSameAs(CommonErrorCode.ARGUMENT_INVALID);
        assertThat(restored.getMessageArgs()).containsExactly("pageSize");
        assertThat(restored.getMessage()).isEqualTo("参数 pageSize 不合法");
    }

    private static Object roundTrip(Object value) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            return in.readObject();
        }
    }
}
