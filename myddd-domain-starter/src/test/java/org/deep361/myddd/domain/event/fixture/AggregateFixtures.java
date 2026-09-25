package org.deep361.myddd.domain.event.fixture;

import org.deep361.myddd.domain.event.fixture.EventFixtures.EventRecorder;
import org.deep361.myddd.domain.event.fixture.EventFixtures.OrderPaidEvent;
import org.deep361.myddd.domain.event.fixture.EventFixtures.OrderShippedEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventListener;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;
import org.deep361.myddd.kernel.ddd.model.AggregateRoot;

import java.time.Instant;

/** 聚合级发布验收夹具：聚合根 + "处理支付后追加发货事件"的监听器。 */
public final class AggregateFixtures {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T00:00:00Z");

    private AggregateFixtures() {
    }

    /** 订单聚合根：{@code pay()} / {@code ship()} 各登记一个领域事件，并保留可断言的领域状态。 */
    public static class OrderAggregate extends AggregateRoot<String> {

        private boolean shipped;

        public OrderAggregate(String id) {
            super(id);
        }

        public void pay() {
            registerEvent(new OrderPaidEvent("e-pay-" + getId(), OCCURRED_AT, String.valueOf(getId())));
        }

        public void ship() {
            shipped = true;
            registerEvent(new OrderShippedEvent("e-ship-" + getId(), OCCURRED_AT, String.valueOf(getId())));
        }

        public boolean isShipped() {
            return shipped;
        }
    }

    /** 提交后监听发货事件：验证批量投递时"每个事件"都进入 AFTER_COMMIT 阶段（不只第一个）。 */
    public static class AfterCommitShippedListenerBean {

        private final EventRecorder recorder;

        public AfterCommitShippedListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        @DomainEventListener
        public void on(OrderShippedEvent event) {
            recorder.record("after-commit-shipped", event);
        }
    }

    /** 处理支付事件时给聚合追加发货事件：验证 drain 循环会投递"投递期间新登记"的事件。 */
    public static class FollowUpListenerBean {

        private final EventRecorder recorder;
        private OrderAggregate aggregate;

        public FollowUpListenerBean(EventRecorder recorder) {
            this.recorder = recorder;
        }

        /** 绑定被观察的聚合（测试装配用；不参与监听器注册）。 */
        public void follow(OrderAggregate aggregate) {
            this.aggregate = aggregate;
        }

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, order = 1)
        public void on(OrderPaidEvent event) {
            recorder.record("follow-up", event);
            if (aggregate != null) {
                aggregate.ship();
            }
        }
    }
}
