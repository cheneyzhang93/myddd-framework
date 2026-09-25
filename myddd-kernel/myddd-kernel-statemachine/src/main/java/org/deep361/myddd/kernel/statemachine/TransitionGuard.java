package org.deep361.myddd.kernel.statemachine;

/**
 * 转移守卫：判断某次流转是否放行。
 *
 * <p><b>契约</b>：(a) 必须<b>无副作用</b>且<b>快速</b>——{@code canFire} 与 {@code fire} 都会调用它，同一次流转
 * 可能被调用多次；(b) 无法判定时返回 {@code false}（拒绝优于放行）；(c) 不做状态写入——状态的读写由
 * {@link StateMachine} 统一负责，守卫只表达"允不允许"。
 */
@FunctionalInterface
public interface TransitionGuard<S, E> {

    /** 判断是否放行。 */
    boolean allow(TransitionContext<S, E> context);
}
