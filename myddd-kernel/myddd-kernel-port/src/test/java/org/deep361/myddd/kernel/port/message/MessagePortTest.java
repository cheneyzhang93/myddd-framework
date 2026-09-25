package org.deep361.myddd.kernel.port.message;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessagePortTest {

    static final class RecordingMessagePort implements MessagePort {
        final List<OutboundMessage> sent = new ArrayList<>();

        @Override
        public void send(OutboundMessage message) {
            sent.add(message);
        }
    }

    @Test
    void convenience_send_should_delegate_with_channel_and_payload() {
        RecordingMessagePort port = new RecordingMessagePort();

        port.send("order-events", "{\"orderId\":\"1\"}");

        assertThat(port.sent).hasSize(1);
        assertThat(port.sent.get(0).channel()).isEqualTo("order-events");
        assertThat(port.sent.get(0).payload()).isEqualTo("{\"orderId\":\"1\"}");
    }

    @Test
    void should_be_a_single_method_contract() {
        assertThat(MessagePort.class.getDeclaredMethods())
                .filteredOn(method -> !method.isDefault())
                // JaCoCo 插桩会给带 default 方法的接口注入合成的 $jacocoInit 方法，不属于端口契约
                .filteredOn(method -> !method.isSynthetic())
                .hasSize(1);
    }
}
