package org.deep361.myddd.limiter.resilience4j;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.limiter.resilience4j.circuit.Resilience4jCircuitBreakerPort;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔端口口的行为面（{@code AB7}–{@code AB13}）：手工三段记账的每一个分支。
 *
 * <p>{@code AB9} 是本组的存在理由：同一个 {@code AssertionError} 走 {@code decorateSupplier} 两次之后
 * {@code state=CLOSED}、{@code failed=0}（{@code Probe46b Q1}），只有手工 {@code onError} 才计得进去
 * （{@code Q2}）。{@code AB12} 钉的是"耗时必须传真差值"：把 {@code 0} 当耗时传进去，慢调用形态永远不触发
 * （{@code R1}/{@code R2}）。
 */
class Resilience4jCircuitBreakerPortTest {

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.ofDefaults());
    private final Resilience4jCircuitBreakerPort port = new Resilience4jCircuitBreakerPort(this.registry);

    /** 两发失败即开闸的配置（与 {@code Probe46b Q2} 同一形态）。 */
    private static CircuitBreakerConfig tripping() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50F)
                .permittedNumberOfCallsInHalfOpenState(1)
                .waitDurationInOpenState(Duration.ofSeconds(60)).build();
    }

    private CircuitBreaker configure(String resource, CircuitBreakerConfig config) {
        this.registry.remove(resource);
        return this.registry.circuitBreaker(resource, config);
    }

    /** AB7：关闭态里动作的返回值原样给回，成功照常计入窗口但不算失败。 */
    @Test
    void should_return_the_action_result_and_record_a_success_while_closed() {
        CircuitBreaker breaker = configure("cb-plain", tripping());
        assertEquals("ok", this.port.execute("cb-plain", () -> "ok"));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
    }

    /**
     * AB8：开闸后拒绝执行动作，拒绝出口只有一条（布尔 {@code tryAcquirePermission()}，
     * 厂商的 {@code CallNotPermittedException} 一次都不出现），消息带业务写的原文资源名（无前缀）。
     */
    @Test
    void should_refuse_the_action_once_open_and_name_the_bare_resource() {
        CircuitBreaker breaker = configure("payment:capture", tripping());
        trip(breaker);
        AtomicInteger executed = new AtomicInteger();

        BizException failure = assertThrows(BizException.class,
                () -> this.port.execute("payment:capture", () -> executed.incrementAndGet()));
        assertThat(executed).hasValue(0);
        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_DEGRADED);
        assertThat(failure.getErrorCode().httpStatus()).isEqualTo(503);
        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getCause()).hasMessageContaining("payment:capture");
    }

    /** AB9：{@code Error} 也要被计数——手工记账这条路线的全部理由（装饰器做不到，{@code Q1}/{@code Q4}）。 */
    @Test
    void should_count_errors_that_are_not_exceptions() {
        CircuitBreaker breaker = configure("cb-error", tripping());
        assertThrows(AssertionError.class, () -> this.port.execute("cb-error", () -> {
            throw new AssertionError("e1");
        }));
        assertThrows(AssertionError.class, () -> this.port.execute("cb-error", () -> {
            throw new AssertionError("e2");
        }));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);

        AtomicInteger executed = new AtomicInteger();
        assertThrows(BizException.class,
                () -> this.port.execute("cb-error", () -> executed.incrementAndGet()));
        assertThat(executed).hasValue(0);
    }

    /** AB10：业务异常原样上抛（同一个实例）且被计数——"抛出去"与"记下来"是同一发调用的两件事。 */
    @Test
    void should_propagate_the_business_failure_unchanged_while_counting_it() {
        CircuitBreaker breaker = configure("cb-count", tripping());
        IllegalStateException business = new IllegalStateException("库存不足");
        assertSame(business, assertThrows(IllegalStateException.class,
                () -> this.port.execute("cb-count", () -> {
                    throw business;
                })));
        // minimumNumberOfCalls=2：一发失败还不够开闸。
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        assertThrows(IllegalStateException.class, () -> this.port.execute("cb-count", () -> {
            throw new IllegalStateException("下游超时");
        }));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * AB11：半开只放一发探测。转换走公开 API {@code transitionToHalfOpenState()}
     * （{@code Probe46d_10}：手工转换 ⇒ {@code state=HALF_OPEN}、第一发放行），因此不必等
     * {@code waitDurationInOpenState}，本组零等待。
     */
    @Test
    void should_permit_exactly_one_probe_in_half_open() {
        CircuitBreaker breaker = configure("cb-probe", tripping());
        trip(breaker);
        breaker.transitionToHalfOpenState();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        AtomicInteger executed = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> this.port.execute("cb-probe", () -> {
            executed.incrementAndGet();
            throw new IllegalStateException("probe-fail");
        }));
        assertThat(executed).hasValue(1);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThrows(BizException.class,
                () -> this.port.execute("cb-probe", () -> executed.incrementAndGet()));
        assertThat(executed).hasValue(1);
    }

    /**
     * AB12：慢调用形态真的在计时——传入的是 {@code System.nanoTime()} 真差值才可能开闸
     * （{@code Probe46c R1}：{@code slowCallRate=100.0} 且 {@code OPEN}；{@code R3} 的快调用仍 {@code CLOSED}）。
     */
    @Test
    void should_trip_on_slow_calls_only_when_the_elapsed_time_is_the_real_one() {
        CircuitBreaker slow = configure("cb-slow", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50F)
                .slowCallRateThreshold(50F).slowCallDurationThreshold(Duration.ofMillis(100))
                .waitDurationInOpenState(Duration.ofSeconds(60)).build());
        assertEquals("ok", this.port.execute("cb-slow", () -> {
            sleep(150L);
            return "ok";
        }));
        assertEquals("ok", this.port.execute("cb-slow", () -> {
            sleep(150L);
            return "ok";
        }));
        assertThat(slow.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(slow.getMetrics().getSlowCallRate()).isEqualTo(100.0F);

        CircuitBreaker fast = configure("cb-fast", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50F)
                .slowCallRateThreshold(50F).slowCallDurationThreshold(Duration.ofMillis(100))
                .waitDurationInOpenState(Duration.ofSeconds(60)).build());
        assertEquals("ok", this.port.execute("cb-fast", () -> "ok"));
        assertEquals("ok", this.port.execute("cb-fast", () -> "ok"));
        assertThat(fast.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(fast.getMetrics().getFailureRate()).isEqualTo(0.0F);
    }

    /** AB13：隔离单位就是资源名——两家规则文件都不写"分组"，一个资源开闸不改别人的行为。 */
    @Test
    void should_isolate_the_open_state_per_resource() {
        CircuitBreaker broken = configure("cb-broken", tripping());
        configure("cb-healthy", tripping());
        trip(broken);

        assertThrows(BizException.class, () -> this.port.execute("cb-broken", () -> "never"));
        assertEquals("fine", this.port.execute("cb-healthy", () -> "fine"));
        assertThat(this.registry.find("cb-healthy").orElseThrow().getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertTrue(this.registry.find("cb-broken").orElseThrow().getState() == CircuitBreaker.State.OPEN);
    }

    /** 两发失败把熔断打到 OPEN（走端口而不是直接调厂商，才证明记账这条路是端口在走）。 */
    private void trip(CircuitBreaker breaker) {
        for (int i = 0; i < 2; i++) {
            assertThrows(IllegalStateException.class, () -> this.port.execute(breaker.getName(), () -> {
                throw new IllegalStateException("下游超时");
            }));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("被中断", interrupted);
        }
    }
}
