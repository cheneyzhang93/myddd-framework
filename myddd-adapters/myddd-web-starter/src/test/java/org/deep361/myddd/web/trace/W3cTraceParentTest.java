package org.deep361.myddd.web.trace;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3C {@code traceparent} 的取值规则：只接受合法形态，其余一律视为"没有"。
 */
class W3cTraceParentTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @Test
    void should_extract_trace_id_from_valid_traceparent() {
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID + "-0000000000000001-01"))
                .contains(TRACE_ID);
    }

    @Test
    void should_accept_other_versions_and_vendor_suffix_fields() {
        assertThat(W3cTraceParent.traceId("01-" + TRACE_ID + "-0000000000000001-00-vendor=beacon"))
                .contains(TRACE_ID);
    }

    @Test
    void should_treat_all_zero_trace_id_as_invalid() {
        assertThat(W3cTraceParent.traceId("00-00000000000000000000000000000000-0000000000000001-01"))
                .isEmpty();
    }

    @Test
    void should_treat_all_zero_parent_id_as_invalid() {
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID + "-0000000000000000-01")).isEmpty();
    }

    @Test
    void should_reject_reserved_version_ff() {
        assertThat(W3cTraceParent.traceId("ff-" + TRACE_ID + "-0000000000000001-01")).isEmpty();
    }

    @Test
    void should_reject_uppercase_hexadecimal() {
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID.toUpperCase() + "-0000000000000001-01")).isEmpty();
    }

    @Test
    void should_reject_wrong_field_length_or_segment_count() {
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID.substring(1) + "-0000000000000001-01")).isEmpty();
        assertThat(W3cTraceParent.traceId("0-" + TRACE_ID + "-0000000000000001-01")).isEmpty();
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID)).isEmpty();
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID + "-0000000000000001-1")).isEmpty();
    }

    @Test
    void should_reject_non_hexadecimal_characters() {
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID.replace('a', 'z') + "-0000000000000001-01")).isEmpty();
    }

    @Test
    void should_reject_malformed_parent_id() {
        // traceparent 的四段各自有长度与字符集契约：父 id 坏掉时不能"顺着 traceId 就认下来"
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID + "-000000000000001-01")).isEmpty();
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID + "-not0hex0parent-01")).isEmpty();
    }

    @Test
    void should_return_empty_for_blank_and_null_without_throwing() {
        assertThat(W3cTraceParent.traceId(null)).isEmpty();
        assertThat(W3cTraceParent.traceId("")).isEmpty();
        assertThat(W3cTraceParent.traceId("   ")).isEmpty();
    }

    @Test
    void should_reject_surrounding_whitespace_and_stay_case_strict() {
        Optional<String> withSpaces = W3cTraceParent.traceId(" 00-" + TRACE_ID + "-0000000000000001-01 ");
        assertThat(withSpaces).isEmpty();
        assertThat(W3cTraceParent.traceId("00-" + TRACE_ID + "-0000000000000001-01")).contains(TRACE_ID);
    }
}
