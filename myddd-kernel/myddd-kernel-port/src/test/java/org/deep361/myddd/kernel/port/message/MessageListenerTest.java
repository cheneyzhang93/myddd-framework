package org.deep361.myddd.kernel.port.message;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MessageListenerTest {

    static class Handler {
        @MessageListener(channel = "order-events")
        void onOrderEvent(String payload) {
        }

        @MessageListener(channel = "points-events", group = "points-consumer", description = "积分事件")
        void onPointsEvent(String payload) {
        }
    }

    @Test
    void should_be_runtime_visible_on_methods_only() {
        assertThat(MessageListener.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(MessageListener.class.getAnnotation(Target.class).value()).containsExactly(ElementType.METHOD);
    }

    @Test
    void should_require_channel_and_default_optional_attributes() throws NoSuchMethodException {
        Method method = Handler.class.getDeclaredMethod("onOrderEvent", String.class);
        MessageListener listener = method.getAnnotation(MessageListener.class);

        assertThat(listener.channel()).isEqualTo("order-events");
        assertThat(listener.group()).isEmpty();
        assertThat(listener.description()).isEmpty();
        assertThat(MessageListener.class.getDeclaredMethod("channel").getDefaultValue()).isNull();
    }

    @Test
    void should_expose_optional_attributes_when_set() throws NoSuchMethodException {
        MessageListener listener = Handler.class.getDeclaredMethod("onPointsEvent", String.class)
                .getAnnotation(MessageListener.class);

        assertThat(listener.group()).isEqualTo("points-consumer");
        assertThat(listener.description()).isEqualTo("积分事件");
    }
}
