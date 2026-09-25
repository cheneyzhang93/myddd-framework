package org.deep361.myddd.kernel.port.resilience;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResiliencePortsTest {

    static final class AlwaysRejectingRateLimitPort implements RateLimitPort {
        @Override
        public boolean tryAcquire(String resource, int permits) {
            return false;
        }
    }

    static final class RecordingCircuitBreakerPort implements CircuitBreakerPort {
        final List<String> resources = new ArrayList<>();

        @Override
        public <T> T execute(String resource, java.util.function.Supplier<T> action) {
            resources.add(resource);
            return action.get();
        }
    }

    @Test
    void rate_limit_port_should_report_rejection() {
        assertThat(new AlwaysRejectingRateLimitPort().tryAcquire("order:create", 1)).isFalse();
    }

    @Test
    void circuit_breaker_execute_should_return_action_result() {
        RecordingCircuitBreakerPort port = new RecordingCircuitBreakerPort();

        String result = port.execute("payment:pay", () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(port.resources).containsExactly("payment:pay");
    }

    @Test
    void circuit_breaker_run_should_delegate_to_execute_and_tolerate_null_result() {
        RecordingCircuitBreakerPort port = new RecordingCircuitBreakerPort();

        port.run("payment:pay", () -> {
        });

        assertThat(port.resources).containsExactly("payment:pay");
    }

    @Test
    void circuit_breaker_execute_should_propagate_action_failure() {
        RecordingCircuitBreakerPort port = new RecordingCircuitBreakerPort();

        assertThatThrownBy(() -> port.execute("payment:pay", () -> {
            throw new IllegalStateException("下游失败");
        })).isInstanceOf(IllegalStateException.class).hasMessage("下游失败");
    }
}
