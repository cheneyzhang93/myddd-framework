package org.deep361.myddd.domain.event;

import org.deep361.myddd.domain.event.fixture.EventFixtures;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringDomainEventPublisherTest {

    @AfterEach
    void cleanup() {
        EventTestSupport.resetTransactionState();
    }

    @Test
    void should_dispatch_in_transaction_then_after_commit() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));
        DomainEventPublisher publisher = new SpringDomainEventPublisher(dispatcherOf(beanFactory));

        EventTestSupport.beginTransaction();
        publisher.publish(paidEvent(), "org.example.order.Order", "o-1");

        assertThat(recorder.names()).containsExactly("paid");
        EventTestSupport.commitTransaction();
        assertThat(recorder.names()).containsExactly("paid", "after-commit");
    }

    @Test
    void should_skip_after_commit_listener_without_transaction() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));

        new SpringDomainEventPublisher(dispatcherOf(beanFactory)).publish(paidEvent(), "org.example.order.Order", "o-1");

        assertThat(recorder.names()).containsExactly("paid");
    }

    @Test
    void should_reject_null_arguments() {
        DomainEventPublisher publisher = new SpringDomainEventPublisher(dispatcherOf(new StaticListableBeanFactory()));

        assertThatThrownBy(() -> publisher.publish(null, "org.example.order.Order", "o-1"))
                .isInstanceOf(NullPointerException.class).hasMessage("领域事件不能为 null");
        assertThatThrownBy(() -> publisher.publish(paidEvent(), null, "o-1"))
                .isInstanceOf(NullPointerException.class).hasMessage("聚合类型不能为 null");
        assertThatThrownBy(() -> publisher.publish(paidEvent(), "org.example.order.Order", null))
                .isInstanceOf(NullPointerException.class).hasMessage("聚合 id 不能为 null");
    }

    private static DomainEventDispatcher dispatcherOf(StaticListableBeanFactory beanFactory) {
        return new DomainEventDispatcher(EventTestSupport.freeze(beanFactory), new TransactionStageExecutor(), Runnable::run);
    }

    private static EventFixtures.OrderPaidEvent paidEvent() {
        return new EventFixtures.OrderPaidEvent("e-1", Instant.parse("2026-09-25T00:00:00Z"), "o-1");
    }
}
