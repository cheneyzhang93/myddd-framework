package org.deep361.myddd.kernel.statemachine;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 状态机运行器：把 {@link StateMachineDefinition} 绑到领域对象的状态字段上驱动流转。
 *
 * <p>状态<b>不在本类中保存</b>：读取走 {@code currentState}（通常指向聚合的状态字段），写入走 {@code stateWriter}
 * （通常写回同一字段）。因此本类无状态、线程安全性等同于被绑定的领域对象；一个聚合实例应绑定一个运行器实例。
 *
 * <p>{@code stateWriter} 必须<b>同步</b>写回 {@code currentState} 读取的同一份数据：{@link #fire(Object)} 写入后直接
 * 返回目标状态、不做二次读取，异步或延迟写回（消息队列、批量刷盘）会让后续 {@link #currentState()} 与返回值不一致。
 *
 * <p><b>恢复语义</b>：从持久化恢复聚合时，用库里读到的状态构造运行器；若该状态不在定义中（数据脏或版本不匹配），
 * 构造时立即抛 {@link IllegalStateException}。
 *
 * <p><b>非法流转</b>：无对应转移或守卫拒绝 → {@link StateMachineException}（错误码 {@code ILLEGAL_STATE}，
 * HTTP 409），且不写入状态；调用方可先用 {@link #canFire(Object)} 探测。
 */
public final class StateMachine<S, E> {

    private final StateMachineDefinition<S, E> definition;
    private final Supplier<S> currentState;
    private final Consumer<S> stateWriter;

    private StateMachine(StateMachineDefinition<S, E> definition, Supplier<S> currentState, Consumer<S> stateWriter) {
        this.definition = Objects.requireNonNull(definition, "definition 不能为 null");
        this.currentState = Objects.requireNonNull(currentState, "currentState 不能为 null");
        this.stateWriter = Objects.requireNonNull(stateWriter, "stateWriter 不能为 null");
        S state = readState();
        if (!definition.states().contains(state)) {
            throw new IllegalStateException("当前状态 " + state + " 未在状态机 " + definition.name()
                    + " 中声明，无法驱动：请检查恢复出的状态与状态机版本是否匹配");
        }
    }

    /**
     * 绑定状态机定义与领域对象的状态读写。
     *
     * @param definition   状态机定义
     * @param currentState 读取当前状态的函数（非 null）
     * @param stateWriter  写回状态的函数（非 null）
     * @throws NullPointerException  任一参数为 null，或读取到的当前状态为 null
     * @throws IllegalStateException 读取到的当前状态未在定义中声明
     */
    public static <S, E> StateMachine<S, E> of(StateMachineDefinition<S, E> definition, Supplier<S> currentState,
                                               Consumer<S> stateWriter) {
        return new StateMachine<>(definition, currentState, stateWriter);
    }

    /** 绑定的定义。 */
    public StateMachineDefinition<S, E> definition() {
        return definition;
    }

    /**
     * 当前状态。
     *
     * @throws NullPointerException 领域对象尚未初始化状态（supplier 返回 null）
     */
    public S currentState() {
        return readState();
    }

    /**
     * 当前状态是否允许执行该事件（守卫拒绝或无转移均为 {@code false}）。
     *
     * @throws NullPointerException event 为 null，或领域对象尚未初始化状态
     */
    public boolean canFire(E event) {
        Objects.requireNonNull(event, "事件不能为 null");
        S from = readState();
        return definition.transition(from, event)
                .filter(transition -> transition.guard().allow(new TransitionContext<>(from, event, transition.to())))
                .isPresent();
    }

    /**
     * 执行流转：校验转移与守卫 → 写回新状态 → 返回新状态。
     *
     * @throws NullPointerException  event 为 null，或领域对象尚未初始化状态
     * @throws StateMachineException 无对应转移或守卫拒绝（此时不写状态）
     */
    public S fire(E event) {
        Objects.requireNonNull(event, "事件不能为 null");
        S from = readState();
        Transition<S, E> transition = definition.requireTransition(from, event);
        if (!transition.guard().allow(new TransitionContext<>(from, event, transition.to()))) {
            throw new StateMachineException(from, event);
        }
        stateWriter.accept(transition.to());
        return transition.to();
    }

    /**
     * 当前状态<b>已声明</b>的事件集合——不含守卫判定，恒假守卫对应的事件也会出现在这里，放行与否请用
     * {@link #canFire(Object)}。
     *
     * @throws NullPointerException 领域对象尚未初始化状态
     */
    public Set<E> availableEvents() {
        return definition.eventsFrom(readState());
    }

    /**
     * 当前状态是否为终态。
     *
     * @throws NullPointerException 领域对象尚未初始化状态
     */
    public boolean isTerminal() {
        return definition.isTerminal(readState());
    }

    private S readState() {
        return Objects.requireNonNull(currentState.get(), "当前状态为 null：领域对象尚未初始化状态");
    }
}
