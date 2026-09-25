package org.deep361.myddd.domain.event.fixture;

import org.deep361.myddd.domain.event.fixture.EventFixtures.EventRecorder;
import org.deep361.myddd.domain.event.fixture.EventFixtures.OrderPaidEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventListener;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;

/** 失败语义测试夹具：抛异常的监听器 + 用于证明"后续监听器仍执行"的幸存者。 */
public final class FailingListeners {

    private FailingListeners() {
    }

    /** 事务内抛运行时异常。 */
    public static class ExplodingListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 1)
        public void on(OrderPaidEvent event) {
            throw new IllegalStateException("boom-in-tx");
        }
    }

    /** 事务内抛受检异常（验证包装策略）。 */
    public static class CheckedExplodingListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 1)
        public void on(OrderPaidEvent event) throws Exception {
            throw new Exception("boom-checked");
        }
    }

    /** 事务内抛 Error（{@link AssertionError}）：必须原样上抛，不得包装成框架异常。 */
    public static class AssertingListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 1)
        public void on(OrderPaidEvent event) {
            throw new AssertionError("boom-error");
        }
    }

    /** 事务内、排在异常监听器之后：失败必须中断它。 */
    public static class SurvivorListenerBean {

        private final EventRecorder recorder;

        public SurvivorListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 2)
        public void on(OrderPaidEvent event) {
            recorder.record("survivor", event);
        }
    }

    /** 提交后抛运行时异常。 */
    public static class AfterCommitExplodingListenerBean {

        @DomainEventListener(order = 1)
        public void on(OrderPaidEvent event) {
            throw new IllegalStateException("boom-after-commit");
        }
    }

    /** 提交后、排在异常监听器之后：失败必须被隔离，它仍要执行。 */
    public static class AfterCommitSurvivorListenerBean {

        private final EventRecorder recorder;

        public AfterCommitSurvivorListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener(order = 2)
        public void on(OrderPaidEvent event) {
            recorder.record("after-commit-survivor", event);
        }
    }

    /** 异步抛运行时异常。 */
    public static class AsyncExplodingListenerBean {

        @DomainEventListener(async = true)
        public void on(OrderPaidEvent event) {
            throw new IllegalStateException("boom-async");
        }
    }
}
