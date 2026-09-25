package org.deep361.myddd.kernel.port.consistency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotentPortTest {

    static final class InMemoryIdempotentPort implements IdempotentPort {
        final Map<String, String> tokens = new HashMap<>();

        @Override
        public boolean tryAcquire(String key, String token, Duration ttl) {
            if (tokens.containsKey(key)) {
                return false;
            }
            tokens.put(key, token);
            return true;
        }

        @Override
        public void release(String key, String token) {
            if (token.equals(tokens.get(key))) {
                tokens.remove(key);
            }
        }
    }

    @Test
    void should_report_first_call_as_acquired_and_second_as_duplicate() {
        InMemoryIdempotentPort port = new InMemoryIdempotentPort();

        assertThat(port.tryAcquire("order:request-1", "token-1", Duration.ofSeconds(60))).isTrue();
        assertThat(port.tryAcquire("order:request-1", "token-2", Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void release_should_allow_retry_after_failure() {
        InMemoryIdempotentPort port = new InMemoryIdempotentPort();
        port.tryAcquire("order:request-1", "token-1", Duration.ofSeconds(60));

        port.release("order:request-1", "token-1");

        assertThat(port.tryAcquire("order:request-1", "token-2", Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void release_with_stale_token_should_leave_placeholder_intact() {
        InMemoryIdempotentPort port = new InMemoryIdempotentPort();
        port.tryAcquire("order:request-1", "token-1", Duration.ofSeconds(60));

        port.release("order:request-1", "token-stale");

        assertThat(port.tryAcquire("order:request-1", "token-2", Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void release_with_foreign_token_should_leave_placeholder_intact() {
        InMemoryIdempotentPort port = new InMemoryIdempotentPort();
        port.tryAcquire("order:request-1", "token-1", Duration.ofSeconds(60));

        port.release("order:request-2", "token-1");

        assertThat(port.tryAcquire("order:request-1", "token-2", Duration.ofSeconds(60))).isFalse();
    }
}
