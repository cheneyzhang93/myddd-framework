package org.deep361.myddd.limiter.resilience4j;

import java.time.Duration;

import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.limiter.resilience4j.circuit.Resilience4jCircuitBreakerPort;
import org.deep361.myddd.testing.contract.CircuitBreakerPortContractTestBase;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * {@code CB-C1}–{@code CB-C7} 在 Resilience4j 上的一遍。
 *
 * <p><b>{@code slidingWindowSize} 与 {@code minimumNumberOfCalls} 必须同时下发</b>：基类 javadoc 立的这条
 * 在 r4j 侧是实测过的——两个值互不推导，只下发窗口会拿到一个"永远凑不齐最小调用数"的哑实例。
 * 本类把两者都设成 {@code tripThreshold()}，并把阈值设成 50% ⇒ "恰好 N 次失败后开闸"（{@code CB-C4}）成立。
 *
 * <p><b>窗口类型是 {@code COUNT_BASED} 而规则面下发的是 {@code TIME_BASED}</b>：这是基类契约与规则文件
 * 之间一条<b>刻意</b>的落差。基类只承诺"配成滑动窗口 {@code tripThreshold()} 个调用"，而我们的
 * {@code degrade} 段为了让两家的语义对齐（Sentinel 的 degrade 统计窗口是时间）只接受时间窗口。
 * 于是本类直接构造 {@code COUNT_BASED} 配置而不走 publisher——这不是绕过规则面，是基类要的那个形状
 * 只有厂商原生配置能给。publisher 自己的 {@code TIME_BASED} 路线由 {@code RS1}、{@code RS11} 与
 * {@code AB10}/{@code AB12} 钉，两条路各管各的。
 * <p>跨家的 {@code tripThreshold()} 不对齐（写进本任务的不做清单，交 Task 47 用规则面对齐）。
 */
class Resilience4jCircuitBreakerPortContractTest extends CircuitBreakerPortContractTestBase {

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.ofDefaults());

    @Override
    protected CircuitBreakerPort circuitBreakerPort() {
        return new Resilience4jCircuitBreakerPort(this.registry);
    }

    @Override
    protected int tripThreshold() {
        return 4;
    }

    @Override
    protected void configure(String resource, int slidingWindowSize) {
        this.registry.remove(resource);
        this.registry.circuitBreaker(resource, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(slidingWindowSize)
                .failureRateThreshold(50F)
                .permittedNumberOfCallsInHalfOpenState(1)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build());
    }
}
