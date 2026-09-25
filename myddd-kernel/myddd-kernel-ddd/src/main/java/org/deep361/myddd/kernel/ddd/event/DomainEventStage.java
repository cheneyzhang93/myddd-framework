package org.deep361.myddd.kernel.ddd.event;

/**
 * 领域事件的投递阶段。
 *
 * <p>{@link #IN_TRANSACTION}：在事务内投递——监听器与产生事件的状态变更共享同一事务，监听器抛异常会回滚
 * 整个事务（适合"同事务强一致"的副作用）。
 *
 * <p>{@link #AFTER_COMMIT}：在事务提交后投递——监听器抛异常<b>不会</b>回滚已提交的事务，且进程崩溃会丢
 * （适合"允许最终缺失"的副作用，如发通知）。需要"不丢"时走 Outbox 通道，而不是把监听器改成
 * {@link #IN_TRANSACTION}。
 *
 * <p>无事务上下文时的语义由装配层定义：{@code AFTER_COMMIT} 监听器<b>直接跳过</b>（不静默降级为事务内执行），
 * {@code IN_TRANSACTION} 监听器按无事务语义正常执行。
 */
public enum DomainEventStage {

    /** 事务内投递：与状态变更同事务，异常即回滚。 */
    IN_TRANSACTION,

    /** 事务提交后投递：失败不回滚、崩溃会丢，适合可容忍缺失的副作用。 */
    AFTER_COMMIT
}
