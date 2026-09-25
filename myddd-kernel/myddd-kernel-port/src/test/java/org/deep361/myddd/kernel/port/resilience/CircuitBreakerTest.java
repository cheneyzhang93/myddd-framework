package org.deep361.myddd.kernel.port.resilience;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    static class Resource {
        @CircuitBreaker(key = "payment:pay", failureRateThreshold = 50, slidingWindowSize = 20,
                waitDurationInOpenState = 10000, fallbackMethod = "payFallback")
        @SuppressWarnings("unused")
        void pay() {
        }

        @CircuitBreaker
        @SuppressWarnings("unused")
        void defaults() {
        }
    }

    @Test
    void should_be_runtime_visible_on_types_and_methods() {
        assertThat(CircuitBreaker.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(CircuitBreaker.class.getAnnotation(Target.class).value())
                .containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
    }

    @Test
    void should_expose_configured_values() throws NoSuchMethodException {
        CircuitBreaker breaker = Resource.class.getDeclaredMethod("pay").getAnnotation(CircuitBreaker.class);

        assertThat(breaker.key()).isEqualTo("payment:pay");
        assertThat(breaker.failureRateThreshold()).isEqualTo(50);
        assertThat(breaker.slidingWindowSize()).isEqualTo(20);
        assertThat(breaker.waitDurationInOpenState()).isEqualTo(10000);
        assertThat(breaker.fallbackMethod()).isEqualTo("payFallback");
    }

    @Test
    void defaults_should_mean_use_external_configuration() throws NoSuchMethodException {
        CircuitBreaker breaker = Resource.class.getDeclaredMethod("defaults").getAnnotation(CircuitBreaker.class);

        assertThat(breaker.key()).isEmpty();
        assertThat(breaker.failureRateThreshold()).isZero();
        assertThat(breaker.slidingWindowSize()).isZero();
        assertThat(breaker.waitDurationInOpenState()).isZero();
        assertThat(breaker.fallbackMethod()).isEmpty();
    }
}
