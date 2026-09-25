package org.deep361.myddd.domain.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxBackoffTest {

    @Test
    void should_grow_exponentially_without_jitter() {
        OutboxBackoff backoff = OutboxBackoff.exponential(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60),
                0.0, () -> 0.0);

        assertThat(backoff.nextDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.nextDelay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.nextDelay(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.nextDelay(4)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void should_cap_delay_at_max() {
        OutboxBackoff backoff = OutboxBackoff.exponential(Duration.ofSeconds(1), 10.0, Duration.ofSeconds(30),
                0.0, () -> 0.0);

        // 基准 = initial × multiplier^(n-1)：1s → 10s → 100s（封顶 30s）→ 30s
        assertThat(backoff.nextDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.nextDelay(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.nextDelay(3)).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.nextDelay(5)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void should_apply_downward_jitter_only() {
        OutboxBackoff backoff = OutboxBackoff.exponential(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60),
                0.5, () -> 0.5);

        assertThat(backoff.nextDelay(1)).isEqualTo(Duration.ofMillis(750));
        assertThat(backoff.nextDelay(2)).isLessThanOrEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void should_clamp_truncated_delay_to_one_nanosecond() {
        OutboxBackoff backoff = OutboxBackoff.exponential(Duration.ofNanos(3), 1.0, Duration.ofMinutes(1),
                0.9, () -> 0.9);

        // 3ns × (1 − 0.9×0.9) = 0.57ns，整除截断为 0：结果必须下钳到 1ns
        assertThat(backoff.nextDelay(1)).isEqualTo(Duration.ofNanos(1)).isPositive();
    }

    @Test
    void should_not_shrink_when_random_source_returns_zero() {
        OutboxBackoff backoff = OutboxBackoff.exponential(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1),
                0.2, () -> 0.0);

        // 抖动因子 = 1 − 0.2×0 = 1：随机源取 0 时不产生任何缩减
        assertThat(backoff.nextDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.nextDelay(2)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void should_support_fixed_interval() {
        OutboxBackoff backoff = OutboxBackoff.fixed(Duration.ofSeconds(3));

        assertThat(backoff.nextDelay(1)).isEqualTo(Duration.ofSeconds(3));
        assertThat(backoff.nextDelay(100)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void should_reject_invalid_configuration() {
        assertThatThrownBy(() -> OutboxBackoff.exponential(Duration.ZERO, 2.0, Duration.ofSeconds(1), 0.0, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initial");
        assertThatThrownBy(() -> OutboxBackoff.exponential(Duration.ofSeconds(1), 0.5, Duration.ofSeconds(10), 0.0, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("multiplier");
        assertThatThrownBy(() -> OutboxBackoff.exponential(Duration.ofSeconds(10), 2.0, Duration.ofSeconds(1), 0.0, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max");
        assertThatThrownBy(() -> OutboxBackoff.exponential(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 1.0, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jitterRatio");
        assertThatThrownBy(() -> OutboxBackoff.exponential(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0.0, null))
                .isInstanceOf(NullPointerException.class).hasMessage("random 不能为 null");
        assertThatThrownBy(() -> OutboxBackoff.fixed(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interval");
        assertThatThrownBy(() -> OutboxBackoff.fixed(Duration.ofSeconds(1)).nextDelay(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failedAttempts");
    }
}
