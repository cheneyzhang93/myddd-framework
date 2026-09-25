package org.deep361.myddd.kernel.port.message;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundMessageTest {

    @Test
    void should_carry_all_fields_and_expose_trace_id() {
        InboundMessage message = new InboundMessage("order-events", "{}", "msg-1",
                Map.of(MessageHeaderNames.TRACE_ID, "trace-9"));

        assertThat(message.channel()).isEqualTo("order-events");
        assertThat(message.payload()).isEqualTo("{}");
        assertThat(message.messageId()).isEqualTo("msg-1");
        assertThat(message.traceId()).contains("trace-9");
    }

    @Test
    void should_expose_empty_trace_id_when_absent() {
        assertThat(new InboundMessage("c", "{}", null, Map.of()).traceId()).isEmpty();
    }

    @Test
    void should_normalize_null_headers_to_empty_map() {
        assertThat(new InboundMessage("c", "{}", null, null).headers()).isEmpty();
    }

    @Test
    void should_tolerate_missing_message_id() {
        assertThat(new InboundMessage("c", "{}", null, Map.of()).messageId()).isNull();
    }

    @Test
    void should_reject_invalid_arguments() {
        assertThatThrownBy(() -> new InboundMessage(null, "{}", null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> new InboundMessage(" ", "{}", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> new InboundMessage("c", null, null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void should_reject_null_header_entries() {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("k", null);

        assertThatThrownBy(() -> new InboundMessage("c", "{}", null, headers))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("headers");

        headers.clear();
        headers.put(null, "v");

        assertThatThrownBy(() -> new InboundMessage("c", "{}", null, headers))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("headers");
    }

    @Test
    void headers_should_be_immutable_copy() {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("k", "v");
        InboundMessage message = new InboundMessage("c", "{}", null, headers);

        headers.put("k", "changed");

        assertThat(message.headers()).containsEntry("k", "v");
        assertThatThrownBy(() -> message.headers().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
