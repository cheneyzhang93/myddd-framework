package org.deep361.myddd.kernel.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceContextTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void should_generate_32_hex_trace_id() {
        assertThat(TraceContext.newTraceId()).matches("[0-9a-f]{32}");
    }

    @Test
    void generated_trace_ids_should_be_unique() {
        Set<String> ids = IntStream.range(0, 1000)
                .mapToObj(index -> TraceContext.newTraceId())
                .collect(Collectors.toSet());

        assertThat(ids).hasSize(1000);
    }

    @Test
    void should_set_and_read_current_trace_id() {
        TraceContext.set("abc123");

        assertThat(TraceContext.currentTraceId()).isEqualTo("abc123");
    }

    @Test
    void set_should_overwrite_existing_trace_id() {
        TraceContext.set("first");
        TraceContext.set("second");

        assertThat(TraceContext.currentTraceId()).isEqualTo("second");
    }

    @Test
    void ensure_should_generate_once_then_reuse() {
        String first = TraceContext.ensureTraceId();

        assertThat(first).matches("[0-9a-f]{32}");
        assertThat(TraceContext.ensureTraceId()).isEqualTo(first);
    }

    @Test
    void ensure_should_reuse_inbound_trace_id() {
        TraceContext.set("inbound-id");

        assertThat(TraceContext.ensureTraceId()).isEqualTo("inbound-id");
    }

    @Test
    void set_should_accept_non_w3c_id_verbatim() {
        String rawId = "a".repeat(64);

        TraceContext.set(rawId);

        assertThat(TraceContext.currentTraceId()).isEqualTo(rawId);
    }

    @Test
    void pooled_thread_should_keep_value_across_tasks_until_cleared() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Callable<String> ensure = TraceContext::ensureTraceId;
            Runnable clear = TraceContext::clear;

            String first = executor.submit(ensure).get();
            String second = executor.submit(ensure).get();

            assertThat(second).isEqualTo(first);

            executor.submit(clear).get();

            assertThat(executor.submit(ensure).get()).isNotEqualTo(first);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void new_trace_id_should_not_bind_current_thread() {
        assertThat(TraceContext.newTraceId()).matches("[0-9a-f]{32}");

        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void trace_id_should_be_isolated_between_threads() throws InterruptedException {
        TraceContext.set("main-thread-id");
        AtomicReference<String> otherThreadValue = new AtomicReference<>("unset");

        Thread other = new Thread(() -> otherThreadValue.set(TraceContext.currentTraceId()));
        other.start();
        other.join();

        assertThat(otherThreadValue.get()).isNull();
        assertThat(TraceContext.currentTraceId()).isEqualTo("main-thread-id");
    }

    @Test
    void should_reject_blank_trace_id() {
        assertThatThrownBy(() -> TraceContext.set("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId");
    }

    @Test
    void should_reject_null_trace_id() {
        assertThatThrownBy(() -> TraceContext.set(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId");
    }

    @Test
    void clear_should_remove_trace_id() {
        TraceContext.set("abc123");

        TraceContext.clear();

        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void current_trace_id_should_be_null_without_value() {
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void header_name_should_be_stable() {
        assertThat(TraceContext.TRACE_ID_HEADER).isEqualTo("X-Trace-Id");
    }
}
