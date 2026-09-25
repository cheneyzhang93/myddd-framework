package org.deep361.myddd.domain.event;

import org.deep361.myddd.domain.event.fixture.AggregateFixtures;
import org.deep361.myddd.domain.event.fixture.EventFixtures;
import org.deep361.myddd.domain.outbox.JacksonOutboxPayloadSerializer;
import org.deep361.myddd.domain.outbox.OutboxChannelResolver;
import org.deep361.myddd.domain.outbox.OutboxRecorder;
import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.domain.outbox.OutboxStore;
import org.deep361.myddd.domain.outbox.TransactionAwareInMemoryOutboxStore;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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

    @Test
    void should_record_outbox_row_before_dispatching() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));
        TransactionAwareInMemoryOutboxStore store = new TransactionAwareInMemoryOutboxStore();
        DomainEventPublisher publisher = new SpringDomainEventPublisher(dispatcherOf(beanFactory), outboxRecorderOf(store));

        EventTestSupport.beginTransaction();
        publisher.publish(paidEvent(), "org.example.order.Order", "o-1");
        assertThat(recorder.names()).containsExactly("paid");
        EventTestSupport.commitTransaction();

        assertThat(recorder.names()).containsExactly("paid", "after-commit");
        assertThat(store.committedRecords()).singleElement().satisfies(record -> {
            assertThat(record.eventId()).isEqualTo("e-1");
            assertThat(record.eventType()).isEqualTo(EventFixtures.OrderPaidEvent.class.getName());
            assertThat(record.aggregateType()).isEqualTo("org.example.order.Order");
            assertThat(record.aggregateId()).isEqualTo("o-1");
            assertThat(record.channel()).isEqualTo("order-events");
            assertThat(record.payload()).contains("\"orderId\":\"o-1\"");
            assertThat(record.traceId()).isNull();
            assertThat(record.status()).isEqualTo(OutboxStatus.PENDING);
        });
    }

    @Test
    void should_record_one_row_per_event_when_publishing_aggregate() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("shippedListenerBean", new EventFixtures.ShippedListenerBean(recorder));
        TransactionAwareInMemoryOutboxStore store = new TransactionAwareInMemoryOutboxStore();
        DomainEventPublisher publisher = new SpringDomainEventPublisher(dispatcherOf(beanFactory), outboxRecorderOf(store));
        AggregateFixtures.OrderAggregate aggregate = new AggregateFixtures.OrderAggregate("o-1");
        aggregate.pay();
        aggregate.ship();

        EventTestSupport.beginTransaction();
        publisher.publish(aggregate);
        EventTestSupport.commitTransaction();

        assertThat(store.committedRecords())
                .extracting(OutboxRecord::eventId, OutboxRecord::aggregateType, OutboxRecord::aggregateId)
                .containsExactlyInAnyOrder(
                        tuple("e-pay-o-1", AggregateFixtures.OrderAggregate.class.getName(), "o-1"),
                        tuple("e-ship-o-1", AggregateFixtures.OrderAggregate.class.getName(), "o-1"));
    }

    @Test
    void should_propagate_outbox_failure_before_any_listener_runs() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));
        OutboxRecorder failingRecorder = new OutboxRecorder(new FailingOutboxStore(),
                new JacksonOutboxPayloadSerializer(), OutboxChannelResolver.byAggregateType(),
                Clock.fixed(Instant.parse("2026-09-25T06:00:00Z"), ZoneOffset.UTC));
        DomainEventPublisher publisher = new SpringDomainEventPublisher(dispatcherOf(beanFactory), failingRecorder);

        EventTestSupport.beginTransaction();
        assertThatThrownBy(() -> publisher.publish(paidEvent(), "org.example.order.Order", "o-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox store 炸了");

        assertThat(recorder.names()).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    private static DomainEventDispatcher dispatcherOf(StaticListableBeanFactory beanFactory) {
        return new DomainEventDispatcher(EventTestSupport.freeze(beanFactory), new TransactionStageExecutor(), Runnable::run);
    }

    private static EventFixtures.OrderPaidEvent paidEvent() {
        return new EventFixtures.OrderPaidEvent("e-1", Instant.parse("2026-09-25T00:00:00Z"), "o-1");
    }

    private static OutboxRecorder outboxRecorderOf(TransactionAwareInMemoryOutboxStore store) {
        return new OutboxRecorder(store, new JacksonOutboxPayloadSerializer(), OutboxChannelResolver.byAggregateType(),
                Clock.fixed(Instant.parse("2026-09-25T06:00:00Z"), ZoneOffset.UTC));
    }

    /** 落库即失败的 Outbox 存储：证明"记录失败发生在任何监听器执行之前"。 */
    private static final class FailingOutboxStore implements OutboxStore {

        @Override
        public void save(OutboxRecord record) {
            throw new IllegalStateException("outbox store 炸了");
        }

        @Override
        public List<OutboxRecord> claimDispatchable(Instant now, Instant leaseUntil, int limit) {
            throw new UnsupportedOperationException("测试替身不实现认领");
        }

        @Override
        public void update(OutboxRecord record) {
            throw new UnsupportedOperationException("测试替身不实现更新");
        }
    }
}
