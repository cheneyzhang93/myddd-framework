package org.deep361.myddd.kernel.ddd.event;

import java.util.List;

/**
 * 领域事件来源：聚合级发布所需的全部能力——标识 + "取事件快照并清空"。
 *
 * <p>本接口是 {@link DomainEventPublisher#publish(DomainEventSource)} 的入参抽象：发布器只需要聚合标识与待投递
 * 事件，不需要知道聚合本身。以此做<b>依赖倒置</b>，依赖方向为 {@code ddd.model → ddd.event}（单向），
 * {@code ddd.event} 不反向依赖 {@code ddd.model}（内核包间禁止成环）。
 *
 * <p>实现方为 {@link org.deep361.myddd.kernel.ddd.model.AggregateRoot}：{@link #getId()} 由实体基类提供，
 * {@link #drainEvents()} 即聚合根的事件取出方法。
 *
 * @param <ID> 聚合标识类型
 */
public interface DomainEventSource<ID> {

    /** 聚合标识，创建后不可变；用于生成事件坐标中的 aggregateId（{@code String.valueOf} 后使用）。 */
    ID getId();

    /**
     * 取出并清空当前收集的领域事件，返回<b>调用时刻的不可变快照</b>（按登记顺序）。
     *
     * <p>语义与调用方契约同 {@link org.deep361.myddd.kernel.ddd.model.AggregateRoot#drainEvents()}。
     */
    List<DomainEvent> drainEvents();
}
