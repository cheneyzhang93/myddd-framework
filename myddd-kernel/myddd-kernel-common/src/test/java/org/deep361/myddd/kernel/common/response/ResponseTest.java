package org.deep361.myddd.kernel.common.response;

import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void ok_should_use_ok_code_and_empty_message() {
        Response<String> response = Response.ok("data");

        assertThat(response.code()).isEqualTo(Response.OK_CODE);
        assertThat(response.message()).isEmpty();
        assertThat(response.data()).isEqualTo("data");
        assertThat(response.successful()).isTrue();
    }

    @Test
    void ok_without_data_should_have_null_data() {
        assertThat(Response.ok().data()).isNull();
    }

    @Test
    void should_carry_trace_id_from_context() {
        TraceContext.set("t-1");

        assertThat(Response.ok().traceId()).isEqualTo("t-1");
    }

    @Test
    void fail_should_carry_trace_id_from_context() {
        TraceContext.set("t-2");

        assertThat(Response.fail(CommonErrorCode.NOT_FOUND).traceId()).isEqualTo("t-2");
    }

    @Test
    void explicit_trace_id_should_win_over_context() {
        TraceContext.set("from-context");

        Response<String> response = new Response<>("OK", "", null, "explicit");

        assertThat(response.traceId()).isEqualTo("explicit");
    }

    @Test
    void fail_should_map_error_code_and_default_message() {
        Response<Void> response = Response.fail(CommonErrorCode.NOT_FOUND);

        assertThat(response.code()).isEqualTo("MYDDD_40401");
        assertThat(response.message()).isEqualTo("资源不存在");
        assertThat(response.data()).isNull();
        assertThat(response.successful()).isFalse();
    }

    @Test
    void fail_should_format_message_args() {
        Response<Void> response = Response.fail(CommonErrorCode.ARGUMENT_INVALID, "pageSize");

        assertThat(response.message()).isEqualTo("参数 pageSize 不合法");
    }

    @Test
    void fail_should_accept_explicit_code_and_message() {
        Response<Void> response = Response.fail("ORDER_40001", "订单不存在");

        assertThat(response.code()).isEqualTo("ORDER_40001");
        assertThat(response.message()).isEqualTo("订单不存在");
    }

    @Test
    void should_normalize_null_message_to_empty() {
        assertThat(Response.fail("ORDER_40001", null).message()).isEmpty();
        assertThat(new Response<>("ORDER_40001", null, null, null).message()).isEmpty();
    }

    @Test
    void should_reject_null_code() {
        assertThatThrownBy(() -> new Response<>(null, "m", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code");
    }

    @Test
    void fail_should_reject_throwable_message_args() {
        assertThatThrownBy(() -> Response.fail(CommonErrorCode.SYSTEM_ERROR, new IllegalStateException("boom")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Throwable");
    }

    @Test
    void ok_code_literal_should_be_stable() {
        assertThat(Response.OK_CODE).isEqualTo("OK");
    }

    @Test
    void trace_id_should_be_null_when_context_is_empty() {
        assertThat(Response.ok().traceId()).isNull();
    }

    @Test
    void trace_id_should_be_snapshotted_at_construction() {
        TraceContext.set("at-construction");

        Response<Void> response = Response.ok();

        TraceContext.clear();

        assertThat(response.traceId()).isEqualTo("at-construction");
    }

    @Test
    void fail_should_treat_null_args_array_as_no_args() {
        Response<Void> response = Response.fail(CommonErrorCode.ARGUMENT_INVALID, (Object[]) null);

        assertThat(response.message()).isEqualTo("参数 {} 不合法");
    }

    @Test
    void fail_should_render_single_null_arg_verbatim() {
        Response<Void> response = Response.fail(CommonErrorCode.ARGUMENT_INVALID, (Object) null);

        assertThat(response.message()).isEqualTo("参数 null 不合法");
    }

    @Test
    void fail_should_treat_bare_null_as_no_args() {
        // 同上：bare null 属"传 null 数组"即无参数，javadoc 的 varargs 陷阱段落由此用例兜底。
        Response<Void> response = Response.fail(CommonErrorCode.ARGUMENT_INVALID, null);

        assertThat(response.message()).isEqualTo("参数 {} 不合法");
    }
}
