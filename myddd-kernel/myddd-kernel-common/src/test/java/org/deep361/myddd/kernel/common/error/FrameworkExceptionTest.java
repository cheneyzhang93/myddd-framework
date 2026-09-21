package org.deep361.myddd.kernel.common.error;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FrameworkExceptionTest {

    @Test
    void should_carry_message() {
        FrameworkException exception = new FrameworkException("非法状态");

        assertThat(exception.getMessage()).isEqualTo("非法状态");
        assertThat(exception.getCause()).isNull();
    }

    @Test
    void should_carry_message_and_cause() {
        Throwable cause = new RuntimeException("db down");

        FrameworkException exception = new FrameworkException("装配失败", cause);

        assertThat(exception.getMessage()).isEqualTo("装配失败");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void should_survive_serialization_round_trip() throws Exception {
        FrameworkException exception = new FrameworkException("装配失败", new IllegalStateException("db down"));

        FrameworkException restored = (FrameworkException) roundTrip(exception);

        assertThat(restored.getMessage()).isEqualTo("装配失败");
        assertThat(restored.getCause()).isInstanceOf(IllegalStateException.class).hasMessage("db down");
    }

    private static Object roundTrip(Object value) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            return in.readObject();
        }
    }
}
