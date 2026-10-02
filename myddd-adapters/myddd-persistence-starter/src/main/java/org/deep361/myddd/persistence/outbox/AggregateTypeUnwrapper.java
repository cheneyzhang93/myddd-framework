package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.deep361.myddd.kernel.ddd.event.DomainEventSource;

import java.util.List;
import java.util.Objects;

/**
 * 聚合类型解包装饰器：把 {@code publish(DomainEventSource)} 里"从代理对象读出来的坐标"换成
 * "从真实聚合读出来的坐标"，再逐事件转发给被装饰的发布器。
 *
 * <p><b>它存在的唯一理由</b>：kernel 默认实现用 {@code source.getClass().getName()} 与
 * {@code String.valueOf(source.getId())} 生成聚合坐标，聚合一旦被代理，前者是代理类名（会被
 * {@code OutboxChannelResolver} 推导成 {@code 0-events} 这类没人订阅的通道），后者因为
 * {@code Entity#getId()} 是 {@code final} 方法而无法被代理转发，读到的是 {@code null}。
 *
 * <p><b>语义与原实现对齐</b>：多批"取快照—投递—再取快照"、按登记顺序逐事件转发、异常原样上抛且不回填事件、
 * {@code null} 源的异常消息一字不差。三参原语 {@code publish(event, aggregateType, aggregateId)} 原样转发，
 * 不改写调用方显式给出的坐标。
 *
 * <p><b>线程安全</b>：本类无可变状态；是否线程安全由被装饰者决定。
 */
public class AggregateTypeUnwrapper implements DomainEventPublisher {

    private final DomainEventPublisher delegate;

    public AggregateTypeUnwrapper(DomainEventPublisher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "被装饰的发布器不能为 null");
    }

    @Override
    public void publish(DomainEvent event, String aggregateType, String aggregateId) {
        delegate.publish(event, aggregateType, aggregateId);
    }

    @Override
    public void publish(DomainEventSource<?> source) {
        Objects.requireNonNull(source, "聚合根不能为 null");
        DomainEventSource<?> aggregate = (DomainEventSource<?>) AggregateTypes.target(source);
        String aggregateType = AggregateTypes.name(aggregate);
        Object aggregateId = aggregate.getId();
        if (aggregateId == null) {
            throw new FrameworkException("发布出来的聚合 id 为 null：" + aggregate.getClass().getName()
                    + "。Entity 的契约是 id 创建即确定且不可为 null，为 null 说明拿到的是拦不到 final 方法的"
                    + "代理实例——请改用仓储返回的聚合实例发布。");
        }
        String idValue = String.valueOf(aggregateId);
        List<DomainEvent> batch = aggregate.drainEvents();
        while (!batch.isEmpty()) {
            for (DomainEvent event : batch) {
                delegate.publish(event, aggregateType, idValue);
            }
            batch = aggregate.drainEvents();
        }
    }
}
