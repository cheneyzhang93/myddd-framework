package org.deep361.myddd.domain.event;

import org.deep361.myddd.domain.outbox.OutboxRecorder;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;

import java.util.Objects;

/**
 * 进程内领域事件发布器：先做 Outbox 同事务记录（可选），再投递 {@code IN_TRANSACTION} 监听器、登记
 * {@code AFTER_COMMIT} 监听器。
 *
 * <p><b>落库先于投递</b>：{@link OutboxRecorder} 存在时，先在本事务内写入 Outbox 记录，然后才执行进程内监听器——
 * 落库失败即抛异常，进程内监听器一个都不执行、{@code AFTER_COMMIT} 钩子也不注册。进程内投递失败会连同业务改动
 * 一起回滚，Outbox 记录随之回滚（同事务），不会产生孤立消息。
 *
 * <p>聚合级 {@link #publish(org.deep361.myddd.kernel.ddd.event.DomainEventSource)}（"取快照—逐个投递—再取快照"
 * 循环）由 kernel 的默认实现提供，本类不覆写——聚合事件与单事件走同一条通道，因此每个事件都会各自落一条记录。
 *
 * <p><b>Outbox 为可选</b>：单参数构造器表示"不做 Outbox 记录"（纯进程内投递）；自动装配在有
 * {@link OutboxRecorder} bean 时才传入。
 */
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final DomainEventDispatcher dispatcher;
    private final OutboxRecorder outboxRecorder;

    /** 纯进程内发布（不做 Outbox 记录）。 */
    public SpringDomainEventPublisher(DomainEventDispatcher dispatcher) {
        this(dispatcher, null);
    }

    /**
     * 带 Outbox 记录的发布。
     *
     * @param dispatcher     进程内事件分发器，不可为 null
     * @param outboxRecorder Outbox 记录器；为 null 表示不记录（要求：{@code myddd.domain.outbox.enabled=false}）
     */
    public SpringDomainEventPublisher(DomainEventDispatcher dispatcher, OutboxRecorder outboxRecorder) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "事件分发器不能为 null");
        this.outboxRecorder = outboxRecorder;
    }

    @Override
    public void publish(DomainEvent event, String aggregateType, String aggregateId) {
        Objects.requireNonNull(event, "领域事件不能为 null");
        Objects.requireNonNull(aggregateType, "聚合类型不能为 null");
        Objects.requireNonNull(aggregateId, "聚合 id 不能为 null");
        if (outboxRecorder != null) {
            outboxRecorder.record(event, aggregateType, aggregateId);
        }
        dispatcher.dispatch(event, DomainEventStage.IN_TRANSACTION);
        dispatcher.dispatch(event, DomainEventStage.AFTER_COMMIT);
    }
}
