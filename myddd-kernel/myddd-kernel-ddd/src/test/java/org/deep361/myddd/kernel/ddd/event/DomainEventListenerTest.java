package org.deep361.myddd.kernel.ddd.event;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventListenerTest {

    static class ListenerFixture {

        @DomainEventListener
        void onDefault(DomainEvent event) {
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, async = true, order = -5)
        void onCustom(DomainEvent event) {
        }
    }

    @Test
    void should_default_to_after_commit_sync_zero_order() throws Exception {
        DomainEventListener annotation = listenerMethod("onDefault").getAnnotation(DomainEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.stage()).isEqualTo(DomainEventStage.AFTER_COMMIT);
        assertThat(annotation.async()).isFalse();
        assertThat(annotation.order()).isZero();
    }

    @Test
    void should_keep_explicit_attributes() throws Exception {
        DomainEventListener annotation = listenerMethod("onCustom").getAnnotation(DomainEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.stage()).isEqualTo(DomainEventStage.IN_TRANSACTION);
        assertThat(annotation.async()).isTrue();
        assertThat(annotation.order()).isEqualTo(-5);
    }

    @Test
    void should_be_retained_at_runtime_and_target_methods() {
        assertThat(DomainEventListener.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(DomainEventListener.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.METHOD);
    }

    @Test
    void should_expose_exactly_two_dispatch_stages() {
        assertThat(DomainEventStage.values())
                .containsExactly(DomainEventStage.IN_TRANSACTION, DomainEventStage.AFTER_COMMIT);
        assertThat(DomainEventStage.valueOf("AFTER_COMMIT")).isSameAs(DomainEventStage.AFTER_COMMIT);
    }

    private static Method listenerMethod(String name) throws NoSuchMethodException {
        return ListenerFixture.class.getDeclaredMethod(name, DomainEvent.class);
    }
}
