package org.deep361.myddd.kernel.port.resilience;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitTest {

    static class Resource {
        @RateLimit(key = "order:create", permits = 100, timeout = 200, message = "下单过于频繁")
        @SuppressWarnings("unused")
        void create() {
        }

        @RateLimit
        @SuppressWarnings("unused")
        void defaults() {
        }
    }

    @Test
    void should_be_runtime_visible_on_types_and_methods() {
        assertThat(RateLimit.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(RateLimit.class.getAnnotation(Target.class).value())
                .containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
    }

    @Test
    void should_expose_configured_values() throws NoSuchMethodException {
        RateLimit rateLimit = Resource.class.getDeclaredMethod("create").getAnnotation(RateLimit.class);

        assertThat(rateLimit.key()).isEqualTo("order:create");
        assertThat(rateLimit.permits()).isEqualTo(100);
        assertThat(rateLimit.timeout()).isEqualTo(200);
        assertThat(rateLimit.message()).isEqualTo("下单过于频繁");
    }

    @Test
    void defaults_should_mean_use_external_configuration() throws NoSuchMethodException {
        RateLimit rateLimit = Resource.class.getDeclaredMethod("defaults").getAnnotation(RateLimit.class);

        assertThat(rateLimit.key()).isEmpty();
        assertThat(rateLimit.permits()).isZero();
        assertThat(rateLimit.timeout()).isZero();
        assertThat(rateLimit.message()).isEmpty();
    }
}
