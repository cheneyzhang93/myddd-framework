package org.deep361.myddd.domain.autoconfigure;

import org.deep361.myddd.domain.event.EventTestSupport;
import org.deep361.myddd.domain.event.fixture.EventFixtures;
import org.deep361.myddd.domain.outbox.JacksonOutboxPayloadSerializer;
import org.deep361.myddd.domain.outbox.OutboxChannelResolver;
import org.deep361.myddd.domain.outbox.OutboxPayloadSerializer;
import org.deep361.myddd.domain.outbox.OutboxRecorder;
import org.deep361.myddd.domain.outbox.OutboxStatus;
import org.deep361.myddd.domain.outbox.OutboxStore;
import org.deep361.myddd.domain.outbox.TransactionAwareInMemoryOutboxStore;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MydddOutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MydddDomainAutoConfiguration.class,
                    MydddDomainEventAutoConfiguration.class,
                    MydddOutboxAutoConfiguration.class))
            .withPropertyValues("myddd.domain.scan-packages=" + EventTestSupport.FIXTURE_PACKAGE)
            .withBean(EventFixtures.EventRecorder.class)
            .withBean(EventFixtures.PaidListenerBean.class);

    @AfterEach
    void cleanup() {
        EventTestSupport.resetTransactionState();
    }

    @Test
    void should_not_register_outbox_beans_by_default() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OutboxRecorder.class);
            assertThat(context).doesNotHaveBean(OutboxStore.class);
            assertThat(context).doesNotHaveBean(OutboxPayloadSerializer.class);
            assertThat(context).doesNotHaveBean(OutboxChannelResolver.class);
        });
    }

    @Test
    void should_fail_fast_when_enabled_without_store() {
        runner.withPropertyValues("myddd.domain.outbox.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("OutboxStore");
        });
    }

    @Test
    void should_register_outbox_beans_and_record_through_publisher() {
        runner.withPropertyValues("myddd.domain.outbox.enabled=true")
                .withBean(TransactionAwareInMemoryOutboxStore.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OutboxRecorder.class);
                    assertThat(context.getBean(MydddOutboxProperties.class).isEnabled()).isTrue();
                    assertThat(context.getBean(OutboxPayloadSerializer.class))
                            .isInstanceOf(JacksonOutboxPayloadSerializer.class);

                    EventTestSupport.beginTransaction();
                    try {
                        context.getBean(DomainEventPublisher.class).publish(
                                new EventFixtures.OrderPaidEvent("e-1", Instant.parse("2026-09-25T00:00:00Z"), "o-1"),
                                "org.example.order.Order", "o-1");
                        EventTestSupport.commitTransaction();
                    } finally {
                        EventTestSupport.resetTransactionState();
                    }

                    assertThat(context.getBean(TransactionAwareInMemoryOutboxStore.class).committedRecords())
                            .singleElement()
                            .satisfies(record -> {
                                assertThat(record.eventId()).isEqualTo("e-1");
                                assertThat(record.channel()).isEqualTo("order-events");
                                assertThat(record.status()).isEqualTo(OutboxStatus.PENDING);
                            });
                });
    }

    @Test
    void should_respect_application_provided_outbox_beans() {
        TransactionAwareInMemoryOutboxStore store = new TransactionAwareInMemoryOutboxStore();
        OutboxPayloadSerializer customSerializer = new StubOutboxPayloadSerializer();
        OutboxChannelResolver customResolver = (event, aggregateType) -> "custom-channel";
        OutboxRecorder customRecorder =
                new OutboxRecorder(store, customSerializer, customResolver);

        runner.withPropertyValues("myddd.domain.outbox.enabled=true")
                .withBean(OutboxStore.class, () -> store)
                .withBean(OutboxPayloadSerializer.class, () -> customSerializer)
                .withBean(OutboxChannelResolver.class, () -> customResolver)
                .withBean(OutboxRecorder.class, () -> customRecorder)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OutboxRecorder.class)).isSameAs(customRecorder);
                    assertThat(context.getBean(OutboxPayloadSerializer.class)).isSameAs(customSerializer);
                    assertThat(context.getBean(OutboxChannelResolver.class)).isSameAs(customResolver);
                });
    }

    /** 委派给框架默认实现的替代序列化器（{@link OutboxPayloadSerializer} 有两个方法，不能用 lambda）。 */
    private static final class StubOutboxPayloadSerializer implements OutboxPayloadSerializer {

        private final OutboxPayloadSerializer delegate = new JacksonOutboxPayloadSerializer();

        @Override
        public String serialize(DomainEvent event) {
            return delegate.serialize(event);
        }

        @Override
        public <T extends DomainEvent> T deserialize(String payload, Class<T> eventType) {
            return delegate.deserialize(payload, eventType);
        }
    }
}
