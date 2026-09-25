package org.deep361.myddd.domain.event;

import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;

import java.util.Objects;

/**
 * 进程内领域事件发布器：先投递 {@code IN_TRANSACTION} 监听器，再登记 {@code AFTER_COMMIT} 监听器。
 *
 * <p>聚合级 {@link #publish(org.deep361.myddd.kernel.ddd.model.AggregateRoot)}（"取快照—逐个投递—再取快照"
 * 循环）由 kernel 的默认实现提供，本类不覆写——聚合事件与单事件走同一条通道。
 *
 * <p>聚合坐标（aggregateType / aggregateId）当前不做进程内分发用，供 Outbox 通道在同事务落库时使用；
 * 本类只做非空校验，避免坐标缺失导致可靠投递无法追踪。
 */
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final DomainEventDispatcher dispatcher;

    public SpringDomainEventPublisher(DomainEventDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "事件分发器不能为 null");
    }

    @Override
    public void publish(DomainEvent event, String aggregateType, String aggregateId) {
        Objects.requireNonNull(event, "领域事件不能为 null");
        Objects.requireNonNull(aggregateType, "聚合类型不能为 null");
        Objects.requireNonNull(aggregateId, "聚合 id 不能为 null");
        dispatcher.dispatch(event, DomainEventStage.IN_TRANSACTION);
        dispatcher.dispatch(event, DomainEventStage.AFTER_COMMIT);
    }
}
