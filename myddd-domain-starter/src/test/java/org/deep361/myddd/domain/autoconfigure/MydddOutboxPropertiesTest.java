package org.deep361.myddd.domain.autoconfigure;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MydddOutboxPropertiesTest {

    @Test
    void should_default_lease_duration_to_thirty_seconds() {
        assertThat(new MydddOutboxProperties().getLeaseDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void should_default_backoff_jitter_ratio_to_point_two() {
        assertThat(new MydddOutboxProperties().getBackoff().getJitterRatio()).isEqualTo(0.2);
    }

    @Test
    void should_reject_non_positive_max_attempts() {
        MydddOutboxProperties properties = new MydddOutboxProperties();
        properties.setMaxAttempts(0);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd.domain.outbox.max-attempts");
    }

    @Test
    void should_reject_zero_lease_duration() {
        MydddOutboxProperties properties = new MydddOutboxProperties();
        properties.setLeaseDuration(Duration.ZERO);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd.domain.outbox.lease-duration");
    }

    @Test
    void should_reject_poll_interval_below_one_millisecond() {
        MydddOutboxProperties properties = new MydddOutboxProperties();
        properties.setPollInterval(Duration.ofNanos(999_999));

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd.domain.outbox.poll-interval");
    }

    @Test
    void should_reject_negative_shutdown_timeout() {
        MydddOutboxProperties properties = new MydddOutboxProperties();
        properties.setShutdownTimeout(Duration.ofNanos(-1));

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd.domain.outbox.shutdown-timeout");
    }

    @Test
    void should_reject_backoff_max_below_initial() {
        MydddOutboxProperties properties = new MydddOutboxProperties();
        properties.getBackoff().setInitial(Duration.ofSeconds(10));
        properties.getBackoff().setMax(Duration.ofSeconds(1));

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd.domain.outbox.backoff.max");
    }

    @Test
    void should_reject_jitter_ratio_of_exactly_one() {
        MydddOutboxProperties properties = new MydddOutboxProperties();
        properties.getBackoff().setJitterRatio(1.0);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd.domain.outbox.backoff.jitter-ratio");
    }
}
