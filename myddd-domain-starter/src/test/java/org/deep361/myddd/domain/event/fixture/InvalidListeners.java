package org.deep361.myddd.domain.event.fixture;

import org.deep361.myddd.domain.event.fixture.EventFixtures.OrderPaidEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventListener;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;

/** 非法监听器签名夹具：每一种都必须在启动期让注册失败。 */
public final class InvalidListeners {

    private InvalidListeners() {
    }

    /** 静态方法。 */
    public static class StaticListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public static void on(OrderPaidEvent event) {
        }
    }

    /** 返回值非 void。 */
    public static class NonVoidListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public int on(OrderPaidEvent event) {
            return 0;
        }
    }

    /** 零参数。 */
    public static class NoParameterListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public void on() {
        }
    }

    /** 两个参数。 */
    public static class TwoParameterListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public void on(OrderPaidEvent event, String extra) {
        }
    }

    /** 参数类型不是 DomainEvent 子类型。 */
    public static class WrongParameterTypeListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        public void on(String event) {
        }
    }

    /** async 与 IN_TRANSACTION 组合。 */
    public static class AsyncInTransactionListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION, async = true)
        public void on(OrderPaidEvent event) {
        }
    }

    /** private 方法。 */
    public static class PrivateListenerBean {

        @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
        private void on(OrderPaidEvent event) {
        }
    }
}
