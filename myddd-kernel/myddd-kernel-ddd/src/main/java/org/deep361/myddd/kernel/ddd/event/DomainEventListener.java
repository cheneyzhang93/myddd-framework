package org.deep361.myddd.kernel.ddd.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 领域事件监听器：标注在当前领域模块内的 Spring bean 方法上。
 *
 * <p><b>方法签名契约</b>：只有一个参数、参数类型是 {@link DomainEvent} 的<b>子类型</b>（具体事件类或
 * {@code DomainEvent} 本身），返回 {@code void}、非静态，且能解析出具体实现——允许标注在接口方法或抽象基类
 * 方法上，装配层按 bean 的实际类型解析为最具体的实现方法（{@code ContractListener#handle} →
 * {@code ContractListenerBean#handle}；{@code BaseListener#on} → {@code SubListener#on}）。签名不合法
 * （静态、private、返回值非 void、参数个数或类型不符）时装配期直接失败，不静默忽略。
 *
 * <p>事件与监听器的匹配按参数类型做"可赋值"判断：参数为 {@code DomainEvent} 时接收全部事件；参数为具体
 * 事件类（或其父类/接口）时只接收该类型及其子类型的事件。
 *
 * <p>{@link #async()} 为 {@code true} 时由装配层的线程池投递（异步监听器必然无法回滚事务，故与
 * {@link DomainEventStage#IN_TRANSACTION} 组合是非法配置，装配期失败）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DomainEventListener {

    /** 投递阶段，默认 {@link DomainEventStage#AFTER_COMMIT}（不干扰主事务）。 */
    DomainEventStage stage() default DomainEventStage.AFTER_COMMIT;

    /** 是否异步投递，默认 {@code false}（同步、在调用线程内执行）。 */
    boolean async() default false;

    /** 同一阶段内的执行顺序，升序；默认 {@code 0}。 */
    int order() default 0;
}
