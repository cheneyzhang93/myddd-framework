package org.deep361.myddd.feign.trace;

import feign.RequestTemplate;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站 traceId 透传的单元测试：直接用 {@link RequestTemplate} 作夹具，不启容器也不发请求。
 *
 * <p>每个用例都以 {@link TraceContext#clear()} 开头或结尾：traceId 是 ThreadLocal 的，用例之间必须互不残留。
 */
class TracePropagationInterceptorTest {

    private static final String W3C_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String UPPERCASE_ID = "0AF7651916CD43DD8448EB211C80319C";
    private static final String ALL_ZERO_ID = "00000000000000000000000000000000";

    private final TracePropagationInterceptor interceptor = new TracePropagationInterceptor();

    @AfterEach
    void clearContext() {
        TraceContext.clear();
    }

    @Test
    void should_generate_and_bind_a_trace_id_when_the_context_is_empty() {
        TraceContext.clear();
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        String sent = onlyValue(template, TraceContext.TRACE_ID_HEADER);
        assertThat(sent).as("拦截器必须把生成出的 id 也绑到当前线程，供本线程后续日志使用")
                .isEqualTo(TraceContext.currentTraceId());
        assertThat(sent).matches("^[0-9a-f]{32}$");
        assertThat(onlyValue(template, TracePropagationInterceptor.TRACE_PARENT_HEADER))
                .matches("^00-" + sent + "-[0-9a-f]{16}-01$");
    }

    @Test
    void should_echo_the_bound_trace_id_and_derive_a_w3c_parent() {
        TraceContext.set(W3C_ID);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(onlyValue(template, TraceContext.TRACE_ID_HEADER)).isEqualTo(W3C_ID);
        assertThat(onlyValue(template, TracePropagationInterceptor.TRACE_PARENT_HEADER))
                .matches("^00-" + W3C_ID + "-[0-9a-f]{16}-01$");
    }

    @Test
    void should_send_no_header_when_the_trace_id_is_not_header_safe() {
        TraceContext.set("not an id\r\ninjected: yes");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).as("含空白与控制字符的 id 会让整个请求被客户端拒绝，宁可不发")
                .isEmpty();
    }

    @Test
    void should_skip_traceparent_when_the_trace_id_is_not_lowercase_hex_32() {
        TraceContext.set(UPPERCASE_ID);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(onlyValue(template, TraceContext.TRACE_ID_HEADER)).isEqualTo(UPPERCASE_ID);
        assertThat(template.headers()).doesNotContainKey(TracePropagationInterceptor.TRACE_PARENT_HEADER);
    }

    @Test
    void should_skip_traceparent_for_an_all_zero_trace_id() {
        TraceContext.set(ALL_ZERO_ID);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(onlyValue(template, TraceContext.TRACE_ID_HEADER)).isEqualTo(ALL_ZERO_ID);
        assertThat(template.headers()).doesNotContainKey(TracePropagationInterceptor.TRACE_PARENT_HEADER);
    }

    @Test
    void should_keep_an_existing_trace_id_header_instead_of_appending_a_second_value() {
        TraceContext.set(W3C_ID);
        RequestTemplate template = new RequestTemplate();
        template.header(TraceContext.TRACE_ID_HEADER, "caller-wins");

        interceptor.apply(template);

        assertThat(values(template, TraceContext.TRACE_ID_HEADER))
                .as("RequestTemplate#header 是追加语义：不做检测就会变成同名两个值")
                .containsExactly("caller-wins");
    }

    @Test
    void should_keep_an_existing_traceparent_and_still_fill_the_missing_trace_id() {
        TraceContext.set(W3C_ID);
        String callerParent = "01-" + ALL_ZERO_ID.substring(0, 32) + "-abcdef0123456789-00";
        RequestTemplate template = new RequestTemplate();
        template.header(TracePropagationInterceptor.TRACE_PARENT_HEADER, callerParent);

        interceptor.apply(template);

        assertThat(onlyValue(template, TracePropagationInterceptor.TRACE_PARENT_HEADER)).isEqualTo(callerParent);
        assertThat(onlyValue(template, TraceContext.TRACE_ID_HEADER)).isEqualTo(W3C_ID);
    }

    @Test
    void should_recognize_an_existing_header_regardless_of_case() {
        TraceContext.set(W3C_ID);
        RequestTemplate template = new RequestTemplate();
        template.header("x-trace-id", "caller-wins");

        interceptor.apply(template);

        assertThat(values(template, TraceContext.TRACE_ID_HEADER)).containsExactly("caller-wins");
    }

    @Test
    void should_treat_a_header_with_no_values_as_absent() {
        TraceContext.set(W3C_ID);
        RequestTemplate template = new RequestTemplate();
        template.headers(Map.of(TraceContext.TRACE_ID_HEADER, List.of()));

        interceptor.apply(template);

        assertThat(onlyValue(template, TraceContext.TRACE_ID_HEADER)).isEqualTo(W3C_ID);
    }

    @Test
    void should_write_both_headers_under_the_exact_wire_names_the_receiving_side_reads() {
        TraceContext.set(W3C_ID);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        Map<String, Collection<String>> wire = wireHeaders(template);
        assertThat(wire.keySet())
                .as("模板视图的 Map 不区分大小写，看不出信头被写成了别的大小写字面量；只有走线视图能")
                .containsExactlyInAnyOrder("X-Trace-Id", "traceparent");
        assertThat(wire.get("X-Trace-Id")).containsExactly(W3C_ID);
        assertThat(String.join("", wire.get("traceparent")))
                .as("traceparent 的四段格式：版本 2 位 + traceId 32 位 + spanId 16 位 + flags 2 位")
                .matches("^00-" + W3C_ID + "-[0-9a-f]{16}-01$");
    }

    /** 把模板装配成真正会发到线上的 {@code Request}，取其信头视图（键的大小写即线上所见）。 */
    private static Map<String, Collection<String>> wireHeaders(RequestTemplate template) {
        return RequestTemplate.from(template)
                .method(feign.Request.HttpMethod.GET)
                .target("http://order-service")
                .resolve(Map.of())
                .request()
                .headers();
    }

    private static Collection<String> values(RequestTemplate template, String name) {
        return template.headers().entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    private static String onlyValue(RequestTemplate template, String name) {
        List<String> values = List.copyOf(values(template, name));
        assertThat(values).as("信头 %s 必须恰好一个值", name).hasSize(1);
        return values.get(0);
    }
}
