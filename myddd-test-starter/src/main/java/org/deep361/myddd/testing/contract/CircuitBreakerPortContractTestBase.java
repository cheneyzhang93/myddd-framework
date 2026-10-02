package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CircuitBreakerPort} 的跨厂商契约：失败计数、开闸、开闸后"不再执行动作"、资源隔离四件事必须同形。
 *
 * <p>⚠️ {@link #tripThreshold()} 是本基类的核心约定：两家都必须配成
 * "滑动窗口 {@code tripThreshold()} 个调用、失败率阈值 50%、最小调用数 = 窗口大小"。
 *  resilience4j 的 {@code slidingWindowSize} 与 {@code minimumNumberOfCalls} 互不推导，
 * 只下发前者会得到一个永不熔断的哑实例 ⇒ 两个 provider 必须两个值一起下发，本基类用"恰好 N 次失败后开闸"钉住它。
 */
public abstract class CircuitBreakerPortContractTestBase {

    /** 被测端口。 */
    protected abstract CircuitBreakerPort circuitBreakerPort();

    /** 需要连续失败多少次开闸（两个 provider 都由各自的默认规则下发成同一个值）。 */
    protected abstract int tripThreshold();

    /** 该资源的规则下发缝（窗口大小 = {@code tripThreshold()}、阈值 50%、开闸时长）。 */
    protected abstract void configure(String resource, int slidingWindowSize);

    /** CB-C1：关闭态下动作结果原样返回。 */
    @Test
    void should_return_the_action_result_while_closed() {
        configure("cb-order-query", tripThreshold());
        assertEquals("ok", circuitBreakerPort().execute("cb-order-query", () -> "ok"));
    }

    /** CB-C2：{@code run} 默认方法必须执行且只执行一次。 */
    @Test
    void should_run_the_runnable_exactly_once_through_the_default_method() {
        configure("cb-order-query", tripThreshold());
        AtomicInteger counter = new AtomicInteger();
        circuitBreakerPort().run("cb-order-query", counter::incrementAndGet);
        assertEquals(1, counter.get());
    }

    /** CB-C3：关闭态里的业务异常原样上抛（不包装、不吞）。 */
    @Test
    void should_propagate_business_failure_unchanged_while_closed() {
        configure("cb-order-query", tripThreshold());
        IllegalStateException business = new IllegalStateException("库存不足");
        assertSame(business, assertThrows(IllegalStateException.class,
                () -> circuitBreakerPort().execute("cb-order-query", () -> {
                    throw business;
                })));
    }

    /** CB-C4：达到阈值后开闸，且开闸后的调用**根本不执行动作**。 */
    @Test
    void should_stop_executing_the_action_once_open() {
        int threshold = tripThreshold();
        configure("cb-order-query", threshold);
        for (int i = 0; i < threshold; i++) {
            assertThrows(IllegalStateException.class,
                    () -> circuitBreakerPort().execute("cb-order-query", () -> {
                        throw new IllegalStateException("下游超时");
                    }));
        }
        AtomicInteger executed = new AtomicInteger();
        assertThrows(BizException.class,
                () -> circuitBreakerPort().execute("cb-order-query", () -> executed.incrementAndGet()));
        assertEquals(0, executed.get(), "熔断已开闸却仍然执行了业务动作");
    }

    /** CB-C5：开闸后的失败必须是框架语义的 503（web 侧才有正确的降级出口）。 */
    @Test
    void should_report_the_open_state_as_service_degraded() {
        int threshold = tripThreshold();
        configure("cb-order-query", threshold);
        for (int i = 0; i < threshold; i++) {
            assertThrows(IllegalStateException.class,
                    () -> circuitBreakerPort().execute("cb-order-query", () -> {
                        throw new IllegalStateException("下游超时");
                    }));
        }
        BizException failure = assertThrows(BizException.class,
                () -> circuitBreakerPort().execute("cb-order-query", () -> "never"));
        assertEquals(CommonErrorCode.SERVICE_DEGRADED, failure.getErrorCode());
        assertEquals(503, failure.getErrorCode().httpStatus());
    }

    /** CB-C6：一个资源开闸不影响另一个资源。 */
    @Test
    void should_isolate_the_open_state_per_resource() {
        int threshold = tripThreshold();
        configure("cb-broken", threshold);
        configure("cb-healthy", threshold);
        for (int i = 0; i < threshold; i++) {
            assertThrows(IllegalStateException.class,
                    () -> circuitBreakerPort().execute("cb-broken", () -> {
                        throw new IllegalStateException("下游超时");
                    }));
        }
        assertEquals("fine", circuitBreakerPort().execute("cb-healthy", () -> "fine"));
    }

    /** CB-C7：资源名空白/null 快速失败。 */
    @Test
    void should_reject_an_illegal_resource_name() {
        assertThrows(IllegalArgumentException.class, () -> circuitBreakerPort().execute("  ", () -> "x"));
        assertThrows(IllegalArgumentException.class, () -> circuitBreakerPort().execute(null, () -> "x"));
        assertTrue(tripThreshold() > 0, "阈值必须是正数，否则基类的开闸循环一条都不跑");
    }
}
