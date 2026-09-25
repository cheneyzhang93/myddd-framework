package org.deep361.myddd.kernel.ddd.event;

import org.deep361.myddd.kernel.ddd.model.AggregateRoot;

import java.util.List;
import java.util.Objects;

/**
 * 领域事件发布器：领域层发布事件的唯一出口。
 *
 * <p>本接口在 kernel 内（零框架依赖，红线）；实现由领域装配模块提供（进程内分发见 Task 13，Outbox 记录见
 * Task 16），持久化与消息中间件的适配器在 Plan 3。
 *
 * <p><b>两种发布方式</b>：
 * <ul>
 *   <li>{@link #publish(DomainEvent, String, String)}：原语——单个事件 + 聚合坐标（聚合类型、聚合 id），
 *       投递通道由实现决定；实现方负责拒绝 {@code null} 参数。</li>
 *   <li>{@link #publish(AggregateRoot)}：聚合级默认实现——见方法 javadoc。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：聚合级发布是<b>尽力而为</b>——已投递的事件不回滚，未投递的尾部事件<b>不保证重放</b>；
 * 需要"至少一次、可重放"时用 Outbox 通道在同一事务内记录事件（Task 16/17），不要依赖本默认实现。
 */
public interface DomainEventPublisher {

    /**
     * 发布单个领域事件。
     *
     * @param event         领域事件，不可为 null
     * @param aggregateType 聚合类型（通常为聚合根类的二进制类名），不可为 null
     * @param aggregateId   聚合 id 的字符串形式，不可为 null
     */
    void publish(DomainEvent event, String aggregateType, String aggregateId);

    /**
     * 发布聚合当前收集的全部事件（按登记顺序），并把它们移出聚合。
     *
     * <p>实现步骤：{@link AggregateRoot#drainEvents()} 取一批快照 → 按序逐个调用
     * {@link #publish(DomainEvent, String, String)}（聚合坐标为 {@code aggregate.getClass().getName()} 与
     * {@code String.valueOf(aggregate.getId())}）→ 若投递期间聚合又被登记了新事件，则再取一批，直到聚合无事件。
     *
     * <p><b>失败语义</b>：投递中途抛异常时异常原样上抛；已 drain 出聚合的事件<b>不会</b>被重新登记（聚合不提供
     * 回填入口），因此未投递的事件不会在本进程内重放——"不丢"由同一事务内的 Outbox 记录负责。监听器不得对同一
     * 聚合无限登记事件，否则本方法不终止。
     *
     * @param aggregate 聚合根，不可为 null
     */
    default void publish(AggregateRoot<?> aggregate) {
        Objects.requireNonNull(aggregate, "聚合根不能为 null");
        String aggregateType = aggregate.getClass().getName();
        String aggregateId = String.valueOf(aggregate.getId());
        List<DomainEvent> batch = aggregate.drainEvents();
        while (!batch.isEmpty()) {
            for (DomainEvent event : batch) {
                publish(event, aggregateType, aggregateId);
            }
            batch = aggregate.drainEvents();
        }
    }
}
