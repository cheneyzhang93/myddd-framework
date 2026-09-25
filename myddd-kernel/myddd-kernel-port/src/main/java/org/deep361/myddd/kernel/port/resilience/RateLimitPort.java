package org.deep361.myddd.kernel.port.resilience;

/**
 * 限流端口：尝试获取许可，由适配器实现（Plan 4：sentinel / resilience4j）。
 *
 * <p><b>语义</b>：非阻塞——{@code timeout} 为 0 时不等待，获取不到立即返回 {@code false}；
 * 适配器切面据此抛 {@code BizException(CommonErrorCode.TOO_MANY_REQUESTS)}（HTTP 429）。
 *
 * <p><b>实现契约</b>：{@code resource} 不可为空白、{@code permits} 必须为正数（违反抛
 * {@link IllegalArgumentException}）；实现须线程安全。
 */
public interface RateLimitPort {

    /**
     * 尝试获取许可。
     *
     * @param resource 资源名（限流规则的唯一标识，如 {@code order:create}）
     * @param permits  本次请求消耗的许可数，必须 ≥ 1
     * @return {@code true} 获取成功；{@code false} 被限流
     */
    boolean tryAcquire(String resource, int permits);
}
