package org.deep361.myddd.resiliencefixture;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;

/**
 * 熔断端口的替身：前 {@code grantCalls} 次执行动作，之后按内核 javadoc 抛
 * {@code BizException(SERVICE_DEGRADED)}。
 *
 * <p>{@code actionStarted} 是这只替身存在的唯一理由：端口"打开"与业务"自己在动作里抛
 * {@code SERVICE_DEGRADED}"在异常类型上完全一样，只有"动作有没有被调起来"能区分二者。
 * 切面正是用这一个布尔值决定要不要走 fallback（`CB2`），所以它必须由替身提供而不是由测试猜。
 */
public final class RecordingCircuitBreakerPort implements CircuitBreakerPort {

    /** 每次调用收到的资源名，顺序即调用顺序。 */
    public final List<String> resources = new ArrayList<>();

    /** 端口"看到的"业务异常类型，用于钉"失败率照样记得到"（`CB13`）。 */
    public final List<Class<?>> failureTypes = new ArrayList<>();

    /** 前几次放行；{@code 0} 表示一次都不放行（熔断已打开）。 */
    public int grantCalls = Integer.MAX_VALUE;

    /** 本次调用中动作是否真的跑起来了；每次进入 {@code execute} 时重置为 {@code false}。 */
    public boolean actionStarted;

    @Override
    public <T> T execute(String resource, Supplier<T> action) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("CircuitBreakerPort 的实现契约：resource 不可为空白，拿到 ["
                    + resource + "]");
        }
        this.resources.add(resource);
        this.actionStarted = false;
        if (this.resources.size() > this.grantCalls) {
            throw new BizException(CommonErrorCode.SERVICE_DEGRADED);
        }
        try {
            this.actionStarted = true;
            return action.get();
        } catch (RuntimeException | Error failure) {
            this.failureTypes.add(failure.getClass());
            throw failure;
        }
    }
}
