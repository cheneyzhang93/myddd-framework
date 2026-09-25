package org.deep361.myddd.kernel.statemachine;

import java.util.Objects;

/**
 * 一条转移：{@code from --event--> to}，附带守卫。
 *
 * @param from  源状态
 * @param event 触发事件
 * @param to    目标状态（允许等于 {@code from}，即自转移）
 * @param guard 守卫，不可为 {@code null}（无守卫时用 {@code context -> true}）
 */
public record Transition<S, E>(S from, E event, S to, TransitionGuard<S, E> guard) {

    public Transition {
        Objects.requireNonNull(from, "from 不能为 null");
        Objects.requireNonNull(event, "event 不能为 null");
        Objects.requireNonNull(to, "to 不能为 null");
        Objects.requireNonNull(guard, "guard 不能为 null");
    }

    /** 人类可读描述，用于日志与诊断：{@code CREATED --PAY--> PAID}。 */
    public String describe() {
        return from + " --" + event + "--> " + to;
    }
}
