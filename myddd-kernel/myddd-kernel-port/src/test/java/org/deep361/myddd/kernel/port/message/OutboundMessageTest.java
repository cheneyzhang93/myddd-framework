package org.deep361.myddd.kernel.port.message;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundMessageTest {

    @Test
    void should_carry_all_fields() {
        Instant deliverAt = Instant.parse("2026-09-25T10:00:00Z");

        OutboundMessage message = new OutboundMessage(
                "order-events", "{\"orderId\":\"1\"}", "ORDER-1", Map.of("k", "v"), deliverAt);

        assertThat(message.channel()).isEqualTo("order-events");
        assertThat(message.payload()).isEqualTo("{\"orderId\":\"1\"}");
        assertThat(message.key()).isEqualTo("ORDER-1");
        assertThat(message.headers()).containsEntry("k", "v");
        assertThat(message.deliverAt()).isEqualTo(deliverAt);
    }

    @Test
    void of_should_default_key_headers_and_deliver_at() {
        OutboundMessage message = OutboundMessage.of("order-events", "{}");

        assertThat(message.key()).isNull();
        assertThat(message.headers()).isEmpty();
        assertThat(message.deliverAt()).isNull();
    }

    @Test
    void of_with_key_should_keep_channel_payload_and_key() {
        OutboundMessage message = OutboundMessage.of("order-events", "{}", "ORDER-1");

        assertThat(message.channel()).isEqualTo("order-events");
        assertThat(message.payload()).isEqualTo("{}");
        assertThat(message.key()).isEqualTo("ORDER-1");
        assertThat(message.headers()).isEmpty();
        assertThat(message.deliverAt()).isNull();
    }

    @Test
    void should_expose_null_safe_header_lookup() {
        OutboundMessage message = OutboundMessage.of("order-events", "{}");

        assertThat(message.header("myddd-trace-id")).isEmpty();
        assertThat(new OutboundMessage("c", "p", null, Map.of("myddd-trace-id", "t"), null)
                .header("myddd-trace-id")).contains("t");
    }

    @Test
    void should_normalize_null_headers_to_empty_map() {
        assertThat(new OutboundMessage("c", "p", null, null, null).headers()).isEmpty();
    }

    @Test
    void should_reject_blank_channel() {
        assertThatThrownBy(() -> OutboundMessage.of(" ", "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void should_reject_null_channel() {
        assertThatThrownBy(() -> OutboundMessage.of(null, "p"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void should_reject_null_payload() {
        assertThatThrownBy(() -> OutboundMessage.of("c", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void should_reject_null_header_key() {
        Map<String, String> headers = new HashMap<>();
        headers.put(null, "v");

        assertThatThrownBy(() -> new OutboundMessage("c", "p", null, headers, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("headers");
    }

    @Test
    void should_reject_null_header_value() {
        Map<String, String> headers = new HashMap<>();
        headers.put("k", null);

        assertThatThrownBy(() -> new OutboundMessage("c", "p", null, headers, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("headers");
    }

    @Test
    void headers_should_be_immutable_copy() {
        Map<String, String> headers = new HashMap<>();
        headers.put("k", "v");
        OutboundMessage message = new OutboundMessage("c", "p", null, headers, null);

        headers.put("k", "changed");

        assertThat(message.headers()).containsEntry("k", "v");
        assertThatThrownBy(() -> message.headers().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_implement_value_equality() {
        assertThat(OutboundMessage.of("c", "p")).isEqualTo(OutboundMessage.of("c", "p"));
        assertThat(OutboundMessage.of("c", "p")).hasSameHashCodeAs(OutboundMessage.of("c", "p"));
        assertThat(OutboundMessage.of("c", "p")).isNotEqualTo(OutboundMessage.of("c", "p2"));
    }
}
