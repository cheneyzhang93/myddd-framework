package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.domain.autoconfigure.MydddDomainAutoConfiguration;
import org.deep361.myddd.domain.autoconfigure.MydddDomainEventAutoConfiguration;
import org.deep361.myddd.domain.autoconfigure.MydddOutboxAutoConfiguration;
import org.deep361.myddd.domain.event.EventTestSupport;
import org.deep361.myddd.domain.event.fixture.AggregateFixtures;
import org.deep361.myddd.domain.event.fixture.EventFixtures;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Outbox 投递验收：真实自动装配（记录器 + 存储 + 投递器 + 投递循环）下"业务提交即被投出"。
 *
 * <p>覆盖五条不变量：提交前与回滚后不投出；提交后投出且消息头带事件 id / 类型；投递失败按退避重试（至少一次）；
 * 投递循环随容器启停；聚合级发布下每个事件各一条记录、各一条消息。
 *
 * <p>确定性来源：需要精确控制投递时机的用例把 {@code poll-interval} 设为 1h 并手动调
 * {@code dispatchOnce()}；需要验证真轮询的用例用 10ms 轮询 + 有界等待（{@code awaitSentCount}），不靠固定 sleep。
 */
class OutboxDeliveryAcceptanceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MydddDomainAutoConfiguration.class,
                    MydddDomainEventAutoConfiguration.class,
                    MydddOutboxAutoConfiguration.class))
            .withPropertyValues("myddd.domain.scan-packages=" + EventTestSupport.FIXTURE_PACKAGE)
            .withBean(EventFixtures.EventRecorder.class)
            .withBean(EventFixtures.PaidListenerBean.class)
            .withBean(TransactionAwareInMemoryOutboxStore.class)
            .withBean(RecordingMessagePort.class);

    @AfterEach
    void cleanup() {
        EventTestSupport.resetTransactionState();
    }

    @Test
    void should_deliver_only_after_commit() {
        runner.withPropertyValues("myddd.domain.outbox.enabled=true", "myddd.domain.outbox.poll-interval=1h")
                .run(context -> {
                    OutboxDispatcher dispatcher = context.getBean(OutboxDispatcher.class);
                    RecordingMessagePort port = context.getBean(RecordingMessagePort.class);

                    EventTestSupport.beginTransaction();
                    context.getBean(DomainEventPublisher.class)
                            .publish(orderPaid("e-1"), "org.example.order.Order", "o-1");

                    assertThat(dispatcher.dispatchOnce().claimed()).isZero();
                    assertThat(port.sent()).isEmpty();

                    EventTestSupport.commitTransaction();

                    assertThat(dispatcher.dispatchOnce().dispatched()).isEqualTo(1);
                    assertThat(context.getBean(TransactionAwareInMemoryOutboxStore.class).committedRecords())
                            .extracting(OutboxRecord::status).containsExactly(OutboxStatus.DISPATCHED);
                    OutboundMessage message = port.sent().get(0);
                    assertThat(message.channel()).isEqualTo("order-events");
                    assertThat(message.key()).isEqualTo("o-1");
                    assertThat(message.payload()).contains("\"orderId\":\"o-1\"");
                    assertThat(message.header(MessageHeaderNames.EVENT_ID)).contains("e-1");
                    assertThat(message.header(MessageHeaderNames.EVENT_TYPE))
                            .contains(EventFixtures.OrderPaidEvent.class.getName());
                    assertThat(message.header(MessageHeaderNames.TRACE_ID)).isEmpty();
                    assertThat(message.deliverAt()).isNull();
                });
    }

    @Test
    void should_not_deliver_when_transaction_rolls_back() {
        runner.withPropertyValues("myddd.domain.outbox.enabled=true", "myddd.domain.outbox.poll-interval=1h")
                .run(context -> {
                    OutboxDispatcher dispatcher = context.getBean(OutboxDispatcher.class);

                    EventTestSupport.beginTransaction();
                    context.getBean(DomainEventPublisher.class)
                            .publish(orderPaid("e-1"), "org.example.order.Order", "o-1");
                    EventTestSupport.rollbackTransaction();

                    assertThat(context.getBean(TransactionAwareInMemoryOutboxStore.class).committedRecords()).isEmpty();
                    assertThat(dispatcher.dispatchOnce().claimed()).isZero();
                    assertThat(context.getBean(RecordingMessagePort.class).sent()).isEmpty();
                });
    }

    @Test
    void should_retry_failed_delivery_until_port_succeeds() {
        runner.withPropertyValues("myddd.domain.outbox.enabled=true", "myddd.domain.outbox.poll-interval=10ms",
                        "myddd.domain.outbox.backoff.initial=10ms", "myddd.domain.outbox.backoff.multiplier=1",
                        "myddd.domain.outbox.backoff.max=10ms", "myddd.domain.outbox.backoff.jitter-ratio=0")
                .run(context -> {
                    RecordingMessagePort port = context.getBean(RecordingMessagePort.class);
                    TransactionAwareInMemoryOutboxStore store = context.getBean(TransactionAwareInMemoryOutboxStore.class);
                    port.failNext(1);

                    publishAndCommit(context, "e-1");

                    assertThat(port.awaitSentCount(1, Duration.ofSeconds(5))).isTrue();
                    // send 返回 ≠ 已标记：投递线程先 send 再 update(dispatched)，故等终态而非直接读（否则读到中间态）
                    assertThat(store.awaitStatus("e-1", OutboxStatus.DISPATCHED, Duration.ofSeconds(5))).isTrue();
                    assertThat(store.committedRecords())
                            .extracting(OutboxRecord::status, OutboxRecord::retryCount)
                            .containsExactly(tuple(OutboxStatus.DISPATCHED, 1));
                });
    }

    @Test
    void should_deliver_through_running_dispatch_loop_and_stop_with_context() {
        AtomicReference<OutboxDispatchLoop> loopRef = new AtomicReference<>();

        runner.withPropertyValues("myddd.domain.outbox.enabled=true", "myddd.domain.outbox.poll-interval=10ms")
                .run(context -> {
                    OutboxDispatchLoop loop = context.getBean(OutboxDispatchLoop.class);
                    loopRef.set(loop);
                    assertThat(loop.isRunning()).isTrue();

                    publishAndCommit(context, "e-1");

                    assertThat(context.getBean(RecordingMessagePort.class).awaitSentCount(1, Duration.ofSeconds(5)))
                            .isTrue();
                    TransactionAwareInMemoryOutboxStore store = context.getBean(TransactionAwareInMemoryOutboxStore.class);
                    assertThat(store.awaitStatus("e-1", OutboxStatus.DISPATCHED, Duration.ofSeconds(5))).isTrue();
                    assertThat(store.committedRecords())
                            .extracting(OutboxRecord::status).containsExactly(OutboxStatus.DISPATCHED);
                });

        assertThat(loopRef.get().isRunning()).isFalse();
    }

    @Test
    void should_deliver_every_event_of_aggregate_publish() {
        runner.withPropertyValues("myddd.domain.outbox.enabled=true", "myddd.domain.outbox.poll-interval=1h")
                .run(context -> {
                    AggregateFixtures.OrderAggregate aggregate = new AggregateFixtures.OrderAggregate("o-1");
                    aggregate.pay();
                    aggregate.ship();

                    EventTestSupport.beginTransaction();
                    context.getBean(DomainEventPublisher.class).publish(aggregate);
                    EventTestSupport.commitTransaction();

                    assertThat(context.getBean(OutboxDispatcher.class).dispatchOnce().dispatched()).isEqualTo(2);
                    assertThat(context.getBean(RecordingMessagePort.class).sent())
                            .extracting(message -> message.header(MessageHeaderNames.EVENT_ID).orElseThrow())
                            .containsExactly("e-pay-o-1", "e-ship-o-1");
                });
    }

    private static void publishAndCommit(ApplicationContext context, String eventId) {
        EventTestSupport.beginTransaction();
        context.getBean(DomainEventPublisher.class).publish(orderPaid(eventId), "org.example.order.Order", "o-1");
        EventTestSupport.commitTransaction();
    }

    private static EventFixtures.OrderPaidEvent orderPaid(String eventId) {
        return new EventFixtures.OrderPaidEvent(eventId, OCCURRED_AT, "o-1");
    }
}
