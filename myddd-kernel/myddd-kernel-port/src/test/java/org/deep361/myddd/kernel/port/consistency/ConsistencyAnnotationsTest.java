package org.deep361.myddd.kernel.port.consistency;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyAnnotationsTest {

    static class Resource {
        @DistributedLock(key = "#userId", waitTime = 200, leaseTime = 60000, message = "正在处理")
        @SuppressWarnings("unused")
        void locked() {
        }

        @DistributedLock(key = "#userId")
        @SuppressWarnings("unused")
        void lockedDefaults() {
        }

        @Idempotent(key = "#form.requestId", ttl = 120, releaseOnError = false, message = "重复提交")
        @SuppressWarnings("unused")
        void idempotent() {
        }

        @Idempotent
        @SuppressWarnings("unused")
        void idempotentDefaults() {
        }
    }

    @Test
    void distributed_lock_should_be_runtime_visible_on_methods_only() {
        assertThat(DistributedLock.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(DistributedLock.class.getAnnotation(Target.class).value()).containsExactly(ElementType.METHOD);
        assertThat(DistributedLock.class.isAnnotationPresent(Documented.class)).isTrue();
    }

    @Test
    void distributed_lock_should_expose_configured_values_and_defaults() throws NoSuchMethodException {
        DistributedLock configured = Resource.class.getDeclaredMethod("locked").getAnnotation(DistributedLock.class);
        assertThat(configured.key()).isEqualTo("#userId");
        assertThat(configured.waitTime()).isEqualTo(200);
        assertThat(configured.leaseTime()).isEqualTo(60000);
        assertThat(configured.message()).isEqualTo("正在处理");

        DistributedLock defaults = Resource.class.getDeclaredMethod("lockedDefaults")
                .getAnnotation(DistributedLock.class);
        assertThat(defaults.waitTime()).isZero();
        assertThat(defaults.leaseTime()).isEqualTo(30000);
        assertThat(defaults.message()).isEmpty();
        assertThat(DistributedLock.class.getDeclaredMethod("key").getDefaultValue()).isNull();
    }

    @Test
    void idempotent_should_be_runtime_visible_on_methods_only() {
        assertThat(Idempotent.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(Idempotent.class.getAnnotation(Target.class).value()).containsExactly(ElementType.METHOD);
        assertThat(Idempotent.class.isAnnotationPresent(Documented.class)).isTrue();
    }

    @Test
    void idempotent_should_expose_configured_values_and_defaults() throws NoSuchMethodException {
        Idempotent configured = Resource.class.getDeclaredMethod("idempotent").getAnnotation(Idempotent.class);
        assertThat(configured.key()).isEqualTo("#form.requestId");
        assertThat(configured.ttl()).isEqualTo(120);
        assertThat(configured.releaseOnError()).isFalse();
        assertThat(configured.message()).isEqualTo("重复提交");

        Idempotent defaults = Resource.class.getDeclaredMethod("idempotentDefaults").getAnnotation(Idempotent.class);
        assertThat(defaults.key()).isEmpty();
        assertThat(defaults.ttl()).isEqualTo(60);
        assertThat(defaults.releaseOnError()).isTrue();
        assertThat(defaults.message()).isEmpty();
    }
}
