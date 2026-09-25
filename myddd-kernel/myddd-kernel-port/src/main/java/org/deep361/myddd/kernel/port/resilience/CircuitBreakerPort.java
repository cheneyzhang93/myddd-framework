package org.deep361.myddd.kernel.port.resilience;

import java.util.function.Supplier;

/**
 * 熔断端口：在熔断器保护下执行动作，由适配器实现（Plan 4：sentinel / resilience4j）。
 *
 * <p><b>语义</b>：熔断打开或请求被拒绝时，实现必须抛
 * {@code BizException(CommonErrorCode.SERVICE_DEGRADED)}（HTTP 503）；被保护动作自身的异常原样传播，
 * 由实现记录失败率。
 *
 * <p><b>实现契约</b>：{@code resource} 不可为空白（违反抛 {@link IllegalArgumentException}）；
 * 实现须线程安全。
 */
public interface CircuitBreakerPort {

    /**
     * 在熔断器保护下执行动作。
     *
     * @param resource 资源名（熔断器实例的唯一标识，如 {@code payment:pay}）
     * @param action   被保护的动作
     * @param <T>      返回值类型
     * @return 动作返回值
     */
    <T> T execute(String resource, Supplier<T> action);

    /** 无返回值便捷重载。 */
    default void run(String resource, Runnable action) {
        execute(resource, () -> {
            action.run();
            return null;
        });
    }
}
