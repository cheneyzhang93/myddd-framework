package org.deep361.myddd.kernel.port.consistency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotentPortTest {

    static final class InMemoryIdempotentPort implements IdempotentPort {
        final Set<String> keys = new HashSet<>();

        @Override
        public boolean tryAcquire(String key, Duration ttl) {
            return keys.add(key);
        }

        @Override
        public void release(String key) {
            keys.remove(key);
        }
    }

    @Test
    void should_report_first_call_as_acquired_and_second_as_duplicate() {
        InMemoryIdempotentPort port = new InMemoryIdempotentPort();

        assertThat(port.tryAcquire("order:request-1", Duration.ofSeconds(60))).isTrue();
        assertThat(port.tryAcquire("order:request-1", Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void release_should_allow_retry_after_failure() {
        InMemoryIdempotentPort port = new InMemoryIdempotentPort();
        port.tryAcquire("order:request-1", Duration.ofSeconds(60));

        port.release("order:request-1");

        assertThat(port.tryAcquire("order:request-1", Duration.ofSeconds(60))).isTrue();
    }
}
