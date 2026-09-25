package org.deep361.myddd.kernel.statemachine;

import java.util.Objects;

/**
 * 转移上下文：守卫可见的全部信息（源状态、事件、目标状态）。
 *
 * <p>守卫若需要业务数据（如订单金额、会员等级），由定义方在 lambda 中捕获领域对象——上下文本身刻意只承载
 * 转移的结构信息，不引入"外部对象"这种弱类型字段。
 *
 * @param from  源状态
 * @param event 触发事件
 * @param to    目标状态
 */
public record TransitionContext<S, E>(S from, E event, S to) {

    public TransitionContext {
        Objects.requireNonNull(from, "from 不能为 null");
        Objects.requireNonNull(event, "event 不能为 null");
        Objects.requireNonNull(to, "to 不能为 null");
    }
}
