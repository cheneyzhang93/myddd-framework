package org.deep361.myddd.domain.orphan;

import org.deep361.myddd.domain.event.fixture.EventFixtures.OrderPaidEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventListener;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;

/** 不属于任何领域模块的监听器 bean：用于验证"模块归属校验"启动失败。 */
public class OrphanListenerBean {

    @DomainEventListener(stage = DomainEventStage.IN_TRANSACTION)
    public void on(OrderPaidEvent event) {
    }
}
