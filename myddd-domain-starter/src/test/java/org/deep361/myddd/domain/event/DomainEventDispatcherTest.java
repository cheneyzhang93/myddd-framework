package org.deep361.myddd.domain.event;

import org.deep361.myddd.domain.event.fixture.EventFixtures;
import org.deep361.myddd.domain.event.fixture.FailingListeners;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventDispatcherTest {

    @AfterEach
    void cleanup() {
        EventTestSupport.resetTransactionState();
    }

    @Test
    void should_invoke_only_listeners_of_matching_stage() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));

        dispatcherOf(beanFactory).dispatch(paidEvent(), DomainEventStage.IN_TRANSACTION);

        assertThat(recorder.names()).containsExactly("paid");
    }

    @Test
    void should_invoke_only_listeners_accepting_the_event_type() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        beanFactory.addBean("shippedListenerBean", new EventFixtures.ShippedListenerBean(recorder));
        DomainEventDispatcher dispatcher = dispatcherOf(beanFactory);

        dispatcher.dispatch(paidEvent(), DomainEventStage.IN_TRANSACTION);
        dispatcher.dispatch(shippedEvent(), DomainEventStage.IN_TRANSACTION);

        assertThat(recorder.names()).containsExactly("paid", "shipped");
    }

    @Test
    void should_invoke_any_event_listener_for_all_event_types() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("anyEventListenerBean", new EventFixtures.AnyEventListenerBean(recorder));
        DomainEventDispatcher dispatcher = dispatcherOf(beanFactory);

        dispatcher.dispatch(paidEvent(), DomainEventStage.IN_TRANSACTION);
        dispatcher.dispatch(shippedEvent(), DomainEventStage.IN_TRANSACTION);

        assertThat(recorder.names()).containsExactly("any", "any");
    }

    @Test
    void should_invoke_listeners_in_registered_order() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("highOrderListenerBean", new EventFixtures.HighOrderListenerBean(recorder));
        beanFactory.addBean("lowOrderListenerBean", new EventFixtures.LowOrderListenerBean(recorder));

        dispatcherOf(beanFactory).dispatch(paidEvent(), DomainEventStage.IN_TRANSACTION);

        assertThat(recorder.names()).containsExactly("high", "low");
    }

    @Test
    void should_propagate_in_transaction_failure_and_stop_remaining_listeners() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("explodingListenerBean", new FailingListeners.ExplodingListenerBean());
        beanFactory.addBean("survivorListenerBean", new FailingListeners.SurvivorListenerBean(recorder));
        DomainEventDispatcher dispatcher = dispatcherOf(beanFactory);

        assertThatThrownBy(() -> dispatcher.dispatch(paidEvent(), DomainEventStage.IN_TRANSACTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom-in-tx");

        assertThat(recorder.names()).isEmpty();
    }

    @Test
    void should_wrap_checked_failure_of_in_transaction_listener() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("checkedExplodingListenerBean", new FailingListeners.CheckedExplodingListenerBean());

        assertThatThrownBy(() -> dispatcherOf(beanFactory).dispatch(paidEvent(), DomainEventStage.IN_TRANSACTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("抛出受检异常")
                .hasRootCauseMessage("boom-checked");
    }

    @Test
    void should_defer_after_commit_listener_until_commit() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));
        DomainEventDispatcher dispatcher = dispatcherOf(beanFactory);
        EventTestSupport.beginTransaction();

        dispatcher.dispatch(paidEvent(), DomainEventStage.AFTER_COMMIT);

        assertThat(recorder.names()).isEmpty();
        EventTestSupport.commitTransaction();
        assertThat(recorder.names()).containsExactly("after-commit");
    }

    @Test
    void should_skip_after_commit_listener_without_active_transaction() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("defaultStageListenerBean", new EventFixtures.DefaultStageListenerBean(recorder));

        dispatcherOf(beanFactory).dispatch(paidEvent(), DomainEventStage.AFTER_COMMIT);

        assertThat(recorder.names()).isEmpty();
    }

    @Test
    void should_isolate_after_commit_failure_and_continue() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("afterCommitExplodingListenerBean", new FailingListeners.AfterCommitExplodingListenerBean());
        beanFactory.addBean("afterCommitSurvivorListenerBean", new FailingListeners.AfterCommitSurvivorListenerBean(recorder));
        DomainEventDispatcher dispatcher = dispatcherOf(beanFactory);
        EventTestSupport.beginTransaction();

        dispatcher.dispatch(paidEvent(), DomainEventStage.AFTER_COMMIT);
        EventTestSupport.commitTransaction();

        assertThat(recorder.names()).containsExactly("after-commit-survivor");
    }

    @Test
    void should_run_async_listener_after_commit() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("asyncListenerBean", new EventFixtures.AsyncListenerBean(recorder));
        DomainEventDispatcher dispatcher = dispatcherOf(beanFactory);
        EventTestSupport.beginTransaction();

        dispatcher.dispatch(paidEvent(), DomainEventStage.AFTER_COMMIT);

        assertThat(recorder.names()).isEmpty();
        EventTestSupport.commitTransaction();
        assertThat(recorder.names()).containsExactly("async");
    }

    @Test
    void should_skip_async_listener_without_active_transaction() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("asyncListenerBean", new EventFixtures.AsyncListenerBean(recorder));

        dispatcherOf(beanFactory).dispatch(paidEvent(), DomainEventStage.AFTER_COMMIT);

        assertThat(recorder.names()).isEmpty();
    }

    @Test
    void should_isolate_async_listener_failure() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("asyncExplodingListenerBean", new FailingListeners.AsyncExplodingListenerBean());
        beanFactory.addBean("afterCommitSurvivorListenerBean", new FailingListeners.AfterCommitSurvivorListenerBean(recorder));
        DomainEventDispatcher dispatcher = dispatcherOf(beanFactory);
        EventTestSupport.beginTransaction();

        dispatcher.dispatch(paidEvent(), DomainEventStage.AFTER_COMMIT);
        EventTestSupport.commitTransaction();

        assertThat(recorder.names()).containsExactly("after-commit-survivor");
    }

    @Test
    void should_isolate_rejected_async_submission() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("asyncListenerBean", new EventFixtures.AsyncListenerBean(recorder));
        Executor rejecting = task -> {
            throw new RejectedExecutionException("线程池已饱和");
        };
        DomainEventDispatcher dispatcher = dispatcherOf(beanFactory, rejecting);
        EventTestSupport.beginTransaction();

        dispatcher.dispatch(paidEvent(), DomainEventStage.AFTER_COMMIT);
        EventTestSupport.commitTransaction();

        assertThat(recorder.names()).isEmpty();
    }

    @Test
    void should_reject_null_event_and_stage() {
        DomainEventDispatcher dispatcher = dispatcherOf(new StaticListableBeanFactory());

        assertThatThrownBy(() -> dispatcher.dispatch(null, DomainEventStage.IN_TRANSACTION))
                .isInstanceOf(NullPointerException.class).hasMessage("领域事件不能为 null");
        assertThatThrownBy(() -> dispatcher.dispatch(paidEvent(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("投递阶段不能为 null");
    }

    @Test
    void should_resolve_snapshot_lazily_from_registrar() {
        EventFixtures.EventRecorder recorder = new EventFixtures.EventRecorder();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("paidListenerBean", new EventFixtures.PaidListenerBean(recorder));
        DomainEventListenerRegistrar registrar =
                new DomainEventListenerRegistrar(beanFactory, EventTestSupport.fixtureModules());

        // 分发器 bean 在上下文刷新期间创建（早于注册器冻结），构造期读快照会让应用启动失败
        DomainEventDispatcher dispatcher =
                new DomainEventDispatcher(registrar, new TransactionStageExecutor(), Runnable::run);

        assertThatThrownBy(() -> dispatcher.dispatch(paidEvent(), DomainEventStage.IN_TRANSACTION))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未冻结");
        registrar.afterSingletonsInstantiated();
        dispatcher.dispatch(paidEvent(), DomainEventStage.IN_TRANSACTION);
        assertThat(recorder.names()).containsExactly("paid");
    }

    /** 默认用内联执行器替代线程池：异步任务在提交点同步执行，断言不需要等待。 */
    private static DomainEventDispatcher dispatcherOf(StaticListableBeanFactory beanFactory) {
        return dispatcherOf(beanFactory, Runnable::run);
    }

    private static DomainEventDispatcher dispatcherOf(StaticListableBeanFactory beanFactory, Executor asyncExecutor) {
        return new DomainEventDispatcher(EventTestSupport.freeze(beanFactory), new TransactionStageExecutor(), asyncExecutor);
    }

    private static EventFixtures.OrderPaidEvent paidEvent() {
        return new EventFixtures.OrderPaidEvent("e-1", Instant.parse("2026-09-25T00:00:00Z"), "o-1");
    }

    private static EventFixtures.OrderShippedEvent shippedEvent() {
        return new EventFixtures.OrderShippedEvent("e-2", Instant.parse("2026-09-25T00:00:00Z"), "o-1");
    }
}
