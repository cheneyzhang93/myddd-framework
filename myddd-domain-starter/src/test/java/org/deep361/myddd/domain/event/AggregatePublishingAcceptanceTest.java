package org.deep361.myddd.domain.event;

import org.deep361.myddd.domain.event.fixture.AggregateFixtures;
import org.deep361.myddd.domain.event.fixture.EventFixtures;
import org.deep361.myddd.domain.event.fixture.FailingListeners;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 聚合级发布验收：drain 循环、尽力而为失败语义、与 AFTER_COMMIT 阶段的协作。 */
class AggregatePublishingAcceptanceTest {

    @AfterEach
    void cleanup() {
        EventTestSupport.resetTransactionState();
    }

    @Test
    void should_publish_all_aggregate_events_in_registration_order() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("shippedListenerBean", new EventFixtures.ShippedListenerBean(recorder));
        AggregateFixtures.OrderAggregate aggregate = new AggregateFixtures.OrderAggregate("o-1");
        aggregate.pay();
        aggregate.ship();

        publisherOf(beanFactory).publish(aggregate);

        assertThat(recorder.names()).containsExactly("paid", "shipped");
        assertThat(aggregate.domainEvents()).isEmpty();
    }

    @Test
    void should_publish_nothing_when_aggregate_has_no_events() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));

        publisherOf(beanFactory).publish(new AggregateFixtures.OrderAggregate("o-1"));

        assertThat(recorder.names()).isEmpty();
    }

    @Test
    void should_publish_follow_up_events_registered_during_dispatch() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("shippedListenerBean", new EventFixtures.ShippedListenerBean(recorder));
        AggregateFixtures.FollowUpListenerBean followUp = new AggregateFixtures.FollowUpListenerBean(recorder);
        beanFactory.addBean("followUpListenerBean", followUp);
        AggregateFixtures.OrderAggregate aggregate = new AggregateFixtures.OrderAggregate("o-1");
        followUp.follow(aggregate);
        aggregate.pay();

        publisherOf(beanFactory).publish(aggregate);

        assertThat(recorder.names()).containsExactly("paid", "follow-up", "shipped");
        assertThat(aggregate.isShipped()).isTrue();
        assertThat(aggregate.domainEvents()).isEmpty();
    }

    @Test
    void should_stop_and_not_replay_remaining_events_when_publishing_fails() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("explodingListenerBean", new FailingListeners.ExplodingListenerBean());
        beanFactory.addBean("survivorListenerBean", new FailingListeners.SurvivorListenerBean(recorder));
        AggregateFixtures.OrderAggregate aggregate = new AggregateFixtures.OrderAggregate("o-1");
        aggregate.pay();
        aggregate.ship();

        assertThatThrownBy(() -> publisherOf(beanFactory).publish(aggregate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom-in-tx");

        assertThat(recorder.names()).isEmpty();
        assertThat(aggregate.domainEvents()).isEmpty();
    }

    @Test
    void should_run_after_commit_listener_only_after_commit() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));
        AggregateFixtures.OrderAggregate aggregate = new AggregateFixtures.OrderAggregate("o-1");
        aggregate.pay();
        DomainEventPublisher publisher = publisherOf(beanFactory);

        EventTestSupport.beginTransaction();
        publisher.publish(aggregate);

        assertThat(recorder.names()).containsExactly("paid");
        EventTestSupport.commitTransaction();
        assertThat(recorder.names()).containsExactly("paid", "after-commit");
    }

    private static DomainEventPublisher publisherOf(StaticListableBeanFactory beanFactory) {
        return new SpringDomainEventPublisher(new DomainEventDispatcher(
                EventTestSupport.freeze(beanFactory), new TransactionStageExecutor(), Runnable::run));
    }
}
