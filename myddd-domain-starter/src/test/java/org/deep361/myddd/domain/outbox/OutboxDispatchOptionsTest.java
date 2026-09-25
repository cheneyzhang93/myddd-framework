package org.deep361.myddd.domain.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxDispatchOptionsTest {

    @Test
    void should_reject_non_positive_max_attempts() {
        assertThatThrownBy(() -> new OutboxDispatchOptions(0, 50, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxAttempts");
    }

    @Test
    void should_reject_non_positive_batch_size() {
        assertThatThrownBy(() -> new OutboxDispatchOptions(5, 0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batchSize");
    }

    @Test
    void should_reject_zero_lease_duration() {
        assertThatThrownBy(() -> new OutboxDispatchOptions(5, 50, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaseDuration");
    }

    @Test
    void should_expose_defaults_of_five_attempts_fifty_batch_thirty_second_lease() {
        OutboxDispatchOptions defaults = OutboxDispatchOptions.defaults();

        assertThat(defaults.maxAttempts()).isEqualTo(5);
        assertThat(defaults.batchSize()).isEqualTo(50);
        assertThat(defaults.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
    }
}
