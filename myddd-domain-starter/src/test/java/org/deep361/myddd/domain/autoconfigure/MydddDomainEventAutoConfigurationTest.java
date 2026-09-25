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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void should_fail_on_zero_queue_capacity() {
        runner.withPropertyValues("myddd.domain.event.async.queue-capacity=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("myddd.domain.event.async.queue-capacity");
                });
    }

    @Test
    void should_fail_when_max_pool_size_below_core_pool_size() {
        runner.withPropertyValues(
                        "myddd.domain.event.async.core-pool-size=4",
                        "myddd.domain.event.async.max-pool-size=2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("myddd.domain.event.async.max-pool-size");
                });
    }

    @Test
    void should_fail_when_keep_alive_below_one_second() {
        runner.withPropertyValues("myddd.domain.event.async.keep-alive=500ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("myddd.domain.event.async.keep-alive");
                });
    }

    @Test
    void should_fail_when_shutdown_timeout_below_one_second() {
        runner.withPropertyValues("myddd.domain.event.async.shutdown-timeout=500ms")
                .run(context -> {
                    // 小于 1s 会被截断为 0，等于静默关闭"关闭时等待在飞任务"
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("myddd.domain.event.async.shutdown-timeout");
                });
    }

    @Test
    void should_fail_on_invalid_async_configuration_even_when_user_defines_executor() {
        runner.withPropertyValues("myddd.domain.event.async.queue-capacity=0")
                .withBean(MydddDomainEventAutoConfiguration.ASYNC_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class)
                .run(context -> {
                    // 应用替换线程池 ≠ 配置校验可以被跳过：非法配置一律启动失败
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("myddd.domain.event.async.queue-capacity");
                });
    }

    /**
     * 直连线程池的 destroy() 路径：容器关闭走的是 Spring 7 的"早停机 lifecycle"（本就等待在飞任务），
     * 只有直接 destroy() 才依赖 {@code waitForTasksToCompleteOnShutdown=true}，故本用例单独钉住该配置项。
     */
    @Test
    void should_wait_for_in_flight_task_when_executor_destroyed_directly() {
        runner.withPropertyValues("myddd.domain.event.async.shutdown-timeout=5s")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(
                            MydddDomainEventAutoConfiguration.ASYNC_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
                    CountDownLatch started = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    AtomicBoolean finished = new AtomicBoolean();
                    executor.execute(() -> {
                        started.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        finished.set(true);
                    });
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                    // 任务被闩锁挡住，唯一放行者是 300ms 后的本线程：destroy() 不等就是丢弃在飞任务
                    Thread releaser = new Thread(() -> {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        release.countDown();
                    }, "destroy-releaser");
                    releaser.setDaemon(true);
                    releaser.start();

                    executor.destroy();

                    assertThat(finished.get())
                            .as("destroy() 必须等待在飞任务（waitForTasksToCompleteOnShutdown=true）").isTrue();
                });
    }

    @Test
    void should_wait_for_in_flight_async_listener_on_context_close() {
        runner.withPropertyValues("myddd.domain.event.async.shutdown-timeout=5s")
                .withBean(EventFixtures.EventRecorder.class)
                .withBean(EventFixtures.SlowAsyncListenerBean.class)
                .run(context -> {
                    EventFixtures.SlowAsyncListenerBean listener =
                            context.getBean(EventFixtures.SlowAsyncListenerBean.class);
                    EventTestSupport.beginTransaction();
                    try {
                        context.getBean(DomainEventPublisher.class).publish(paidEvent(), "org.example.order.Order", "o-1");
                        EventTestSupport.commitTransaction();
                    } finally {
                        EventTestSupport.resetTransactionState();
                    }
                    assertThat(listener.started.await(5, TimeUnit.SECONDS)).isTrue();

                    // 在飞任务被闩锁挡住，唯一放行者是 500ms 后的本线程：close() 不等待就观察不到 finished=true
                    Thread releaser = new Thread(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        listener.release();
                    }, "cfg06-releaser");
                    releaser.setDaemon(true);
                    releaser.start();

                    context.close();

                    assertThat(listener.finished()).as("close() 必须等待在飞异步监听器结束").isTrue();
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
