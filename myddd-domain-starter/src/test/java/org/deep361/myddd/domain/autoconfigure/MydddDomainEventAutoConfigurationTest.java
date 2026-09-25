package org.deep361.myddd.domain.autoconfigure;

import org.deep361.myddd.domain.event.DomainEventDispatcher;
import org.deep361.myddd.domain.event.DomainEventListenerRegistrar;
import org.deep361.myddd.domain.event.EventTestSupport;
import org.deep361.myddd.domain.event.fixture.EventFixtures;
import org.deep361.myddd.domain.orphan.OrphanListenerBean;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MydddDomainEventAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MydddDomainAutoConfiguration.class, MydddDomainEventAutoConfiguration.class))
            .withPropertyValues("myddd.domain.scan-packages=" + EventTestSupport.FIXTURE_PACKAGE);

    @Test
    void should_register_event_bus_beans() {
        runner.withBean(EventFixtures.EventRecorder.class)
                .withBean(EventFixtures.PaidListenerBean.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DomainEventPublisher.class);
                    assertThat(context).hasSingleBean(DomainEventDispatcher.class);
                    assertThat(context).hasSingleBean(DomainEventListenerRegistrar.class);
                    assertThat(context.getBean(MydddDomainEventAutoConfiguration.ASYNC_EXECUTOR_BEAN_NAME))
                            .isInstanceOf(ThreadPoolTaskExecutor.class);
                    assertThat(context.getBean(DomainEventListenerRegistrar.class).listenerCount()).isEqualTo(1);
                });
    }

    @Test
    void should_dispatch_end_to_end_inside_transaction() {
        runner.withBean(EventFixtures.EventRecorder.class)
                .withBean(EventFixtures.PaidListenerBean.class)
                .withBean(EventFixtures.DefaultStageListenerBean.class)
                .run(context -> {
                    EventFixtures.EventRecorder recorder = context.getBean(EventFixtures.EventRecorder.class);
                    EventTestSupport.beginTransaction();
                    try {
                        context.getBean(DomainEventPublisher.class).publish(paidEvent(), "org.example.order.Order", "o-1");
                        assertThat(recorder.names()).containsExactly("paid");
                        EventTestSupport.commitTransaction();
                        assertThat(recorder.names()).containsExactly("paid", "after-commit");
                    } finally {
                        EventTestSupport.resetTransactionState();
                    }
                });
    }

    @Test
    void should_run_async_listener_on_dedicated_thread_pool() throws InterruptedException {
        runner.withBean(EventFixtures.EventRecorder.class)
                .withBean(EventFixtures.AsyncListenerBean.class)
                .run(context -> {
                    EventFixtures.EventRecorder recorder = context.getBean(EventFixtures.EventRecorder.class);
                    EventTestSupport.beginTransaction();
                    try {
                        context.getBean(DomainEventPublisher.class).publish(paidEvent(), "org.example.order.Order", "o-1");
                        assertThat(recorder.names()).isEmpty();
                        EventTestSupport.commitTransaction();
                        assertThat(recorder.awaitCount(1, Duration.ofSeconds(10))).isTrue();
                    } finally {
                        EventTestSupport.resetTransactionState();
                    }
                    assertThat(recorder.names()).containsExactly("async");
                    assertThat(recorder.threads()).allMatch(thread -> thread.startsWith("myddd-domain-event-"));
                });
    }

    @Test
    void should_bind_async_executor_properties() {
        runner.withPropertyValues(
                        "myddd.domain.event.async.core-pool-size=2",
                        "myddd.domain.event.async.max-pool-size=6",
                        "myddd.domain.event.async.queue-capacity=32",
                        "myddd.domain.event.async.keep-alive=90s",
                        "myddd.domain.event.async.shutdown-timeout=15s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor executor = context.getBean(
                            MydddDomainEventAutoConfiguration.ASYNC_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(2);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(6);
                    assertThat(executor.getQueueCapacity()).isEqualTo(32);
                    assertThat(executor.getKeepAliveSeconds()).isEqualTo(90);
                });
    }

    @Test
    void should_fail_on_invalid_async_pool_configuration() {
        runner.withPropertyValues("myddd.domain.event.async.core-pool-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("myddd.domain.event.async.core-pool-size");
                });
    }

    @Test
    void should_fail_when_listener_bean_is_outside_modules() {
        runner.withBean(OrphanListenerBean.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    // 该异常从 ClassPath 刷新期的 preInstantiateSingletons 直接上抛（无包装），故断言失败类型本身
                    assertThat(context).getFailure()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("不属于任何 @Domain 领域模块");
                });
    }

    @Test
    void should_not_override_user_defined_publisher() {
        StubPublisher stub = new StubPublisher();

        runner.withBean(DomainEventPublisher.class, () -> stub)
                .run(context -> assertThat(context.getBean(DomainEventPublisher.class)).isSameAs(stub));
    }

    private static EventFixtures.OrderPaidEvent paidEvent() {
        return new EventFixtures.OrderPaidEvent("e-1", Instant.parse("2026-09-25T00:00:00Z"), "o-1");
    }

    /** 用户自定义发布器：验证 @ConditionalOnMissingBean 不覆盖应用 bean。 */
    static final class StubPublisher implements DomainEventPublisher {

        private final List<DomainEvent> published = new ArrayList<>();

        @Override
        public void publish(DomainEvent event, String aggregateType, String aggregateId) {
            published.add(event);
        }
    }
}
