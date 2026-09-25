package org.deep361.myddd.kernel.port.message;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageHeaderNamesTest {

    @Test
    void should_lock_the_wire_contract_of_headers() {
        assertThat(MessageHeaderNames.TRACE_ID).isEqualTo("myddd-trace-id");
        assertThat(MessageHeaderNames.EVENT_ID).isEqualTo("myddd-event-id");
        assertThat(MessageHeaderNames.EVENT_TYPE).isEqualTo("myddd-event-type");
    }

    @Test
    void should_not_be_instantiable() throws NoSuchMethodException {
        Constructor<MessageHeaderNames> constructor = MessageHeaderNames.class.getDeclaredConstructor();

        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
