package org.deep361.myddd.limiter.resilience4j.circuit;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * {@link CircuitBreakerPort} 的 Resilience4j 实现：<b>手工三段记账</b>，不用 {@code decorateSupplier}。
 *
 * <p><b>为什么必须手工</b>：同一个 {@code AssertionError}（{@code Error}，不是 {@code Exception}）走装饰器
 * 两次之后 {@code state=CLOSED}、{@code getNumberOfFailedCalls()=0}，且 {@code recordExceptions(Throwable.class)}
 * 与 {@code recordException(pred)} 都救不了——predicate 对 {@code Error} 一次都没被调用（实测
 * {@code Probe46b Q1/Q4}：{@code invocations=0}）。手工 {@code onError(elapsed, NANOSECONDS, err)} 两次则
 * {@code state=OPEN}、{@code failed=2}、第三发 {@code tryAcquirePermission()=false}（{@code Q2}）。
 * 端口契约要求"业务异常原样上抛、且失败要计数"，装饰器那一族做不到，因为它连 {@code Error} 都进不去。
 * 这与 Sentinel 侧"不调 {@code Tracer.trace} 就根本不打开"是同一件事的两种厂商表达。
 *
 * <p><b>拒绝走布尔那条出口</b>：{@code CircuitBreaker} 同时提供 {@code tryAcquirePermission()}（返回 false）
 * 与 {@code acquirePermission()}（{@code void}，开闸时抛 {@code CallNotPermittedException}，实测
 * {@code Probe46d_9} 的 {@code THREW} 那一格）。端口只有一条拒绝出口 ⇒ 用前者，后者一次都不出现。
 *
 * <p><b>耗时是真差值且单位显式</b>：{@code getTimestampUnit()} 缺省 {@code NANOSECONDS}（{@code Probe46c R0}），
 * 慢调用形态完全依赖传入值——把 {@code 0} 当耗时传进去会让 {@code slow-ratio} 规则变成哑规则
 * （{@code R1} 传真值 ⇒ {@code slowCallRate=100.0} 且 {@code OPEN}）。⇒ 一律 {@code System.nanoTime()} 差值
 * + 显式 {@code TimeUnit.NANOSECONDS}，不调 {@code getCurrentTimestamp()} 那种"看配置脸色"的写法。
 *
 * <p><b>没有规则的资源直接执行动作、不记账、不建实例</b>：与限流侧同一个 {@code find} 理由，
 * 语义等价于 Sentinel 侧"没有 degrade 规则 ⇒ 永不打开"。
 */
public class Resilience4jCircuitBreakerPort implements CircuitBreakerPort {

    private final CircuitBreakerRegistry registry;

    public Resilience4jCircuitBreakerPort(CircuitBreakerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public <T> T execute(String resource, Supplier<T> action) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource 不能为空（r4j 注册表按名字索引）");
        }
        Objects.requireNonNull(action, "action");
        Optional<CircuitBreaker> configured = registry.find(resource);
        if (configured.isEmpty()) {
            return action.get();
        }
        CircuitBreaker breaker = configured.get();
        if (!breaker.tryAcquirePermission()) {
            throw BizException.causedBy(CommonErrorCode.SERVICE_DEGRADED,
                    new IllegalStateException("熔断已开闸，资源 " + resource + " 的调用被拒绝"));
        }
        long start = System.nanoTime();
        try {
            T result = action.get();
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException | Error failure) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, failure);
            throw failure;
        }
    }
}
