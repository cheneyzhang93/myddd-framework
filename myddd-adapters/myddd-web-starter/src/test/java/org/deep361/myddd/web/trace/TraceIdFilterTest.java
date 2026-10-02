package org.deep361.myddd.web.trace;

import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入站取值优先级、响应头回写与线程上下文的成对归还。
 */
class TraceIdFilterTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private final TraceIdFilter filter = new TraceIdFilter();
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/api/orders");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void should_prefer_framework_header_over_traceparent() throws Exception {
        request.addHeader(TraceContext.TRACE_ID_HEADER, TRACE_ID);
        request.addHeader("traceparent", "00-11111111111111111111111111111111-0000000000000001-01");

        AtomicReference<String> inside = new AtomicReference<>();
        filter.doFilter(request, response, (rq, rs) -> inside.set(TraceContext.currentTraceId()));

        assertThat(inside.get()).isEqualTo(TRACE_ID);
        assertThat(response.getHeader(TraceContext.TRACE_ID_HEADER)).isEqualTo(TRACE_ID);
    }

    @Test
    void should_fall_back_to_traceparent_when_framework_header_absent() throws Exception {
        request.addHeader("traceparent", "00-" + TRACE_ID + "-0000000000000001-01");

        AtomicReference<String> inside = new AtomicReference<>();
        filter.doFilter(request, response, (rq, rs) -> inside.set(TraceContext.currentTraceId()));

        assertThat(inside.get()).isEqualTo(TRACE_ID);
        assertThat(response.getHeader(TraceContext.TRACE_ID_HEADER)).isEqualTo(TRACE_ID);
    }

    @Test
    void should_generate_32_hex_trace_id_when_no_header_present() throws Exception {
        AtomicReference<String> inside = new AtomicReference<>();
        filter.doFilter(request, response, (rq, rs) -> inside.set(TraceContext.currentTraceId()));

        assertThat(inside.get()).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceContext.TRACE_ID_HEADER)).isEqualTo(inside.get());
    }

    @Test
    void should_ignore_malformed_traceparent_and_still_emit_trace_id() throws Exception {
        request.addHeader("traceparent", "00-not-a-trace-id-0000000000000001-01");

        AtomicReference<String> inside = new AtomicReference<>();
        filter.doFilter(request, response, (rq, rs) -> inside.set(TraceContext.currentTraceId()));

        assertThat(inside.get()).hasSize(32).doesNotContain("not-a-trace-id");
    }

    @Test
    void should_replace_illegal_framework_header_instead_of_echoing_it() throws Exception {
        String hostile = "abc\r\nX-Trace-Id: injected";
        request.addHeader(TraceContext.TRACE_ID_HEADER, hostile + "0123456789012345678901234567890123456789");

        filter.doFilter(request, response, (rq, rs) -> {
        });

        String published = response.getHeader(TraceContext.TRACE_ID_HEADER);
        assertThat(published).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(published).doesNotContain("injected").doesNotContain("abc");
    }

    @Test
    void should_clear_thread_context_after_request() throws Exception {
        filter.doFilter(request, response, (rq, rs) -> assertThat(TraceContext.currentTraceId()).isNotNull());

        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void should_restore_outer_trace_id_after_nested_request() throws Exception {
        TraceContext.set("outer-trace-id");
        AtomicReference<String> inner = new AtomicReference<>();

        filter.doFilter(request, response, (rq, rs) -> inner.set(TraceContext.currentTraceId()));

        assertThat(inner.get()).isNotEqualTo("outer-trace-id");
        assertThat(TraceContext.currentTraceId()).isEqualTo("outer-trace-id");
    }

    @Test
    void should_emit_same_trace_id_in_body_and_header_through_filter_and_mvc() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TraceProbeController())
                .addFilters(new TraceIdFilter())
                .build();

        mockMvc.perform(get("/probe/trace").header(TraceContext.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.TRACE_ID_HEADER, TRACE_ID))
                .andExpect(content().string(TRACE_ID));
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    /** 只把当前线程的 traceId 原样吐出来，验证"响应体 = 响应头 = ThreadLocal"三者同源。 */
    @RestController
    static class TraceProbeController {

        @GetMapping(value = "/probe/trace", produces = MediaType.TEXT_PLAIN_VALUE)
        String currentTraceId() {
            return String.valueOf(TraceContext.currentTraceId());
        }
    }
}
