package org.deep361.myddd.resilience;

import java.util.List;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.resiliencefixture.ResilienceFixtures;
import org.deep361.myddd.resiliencefixture.ResilienceHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 五格顺序表的弹性侧两格（{@code OR1}–{@code OR4}）：限流在外、熔断在内。
 *
 * <p>判据全部取"另一个端口的调用计数"，不取日志、不取时序：顺序错了要能被读成一个数，
 * 而不是"看起来好像没生效"。
 */
class AspectOrderingTest {

    private final ResilienceHarness harness = new ResilienceHarness();
    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (this.context != null) {
            this.context.close();
        }
    }

    private ResilienceFixtures.CombinedService combined() {
        if (this.context == null) {
            this.context = this.harness.bothAspectContext();
        }
        return this.context.getBean(ResilienceFixtures.CombinedService.class);
    }

    /**
     * {@code OR1}：一次限流拒绝绝不该记成一次熔断失败——它连"有一次调用"都不该发生。
     *
     * <p>读数是熔断端口的资源清单<b>为空</b>：限流在外层就把请求挡下，动作根本没走到熔断那一格。
     */
    @Test
    void should_ask_the_rate_limiter_before_the_circuit_breaker_ever_sees_a_call() {
        this.harness.port.neverGrant();
        ResilienceFixtures.CombinedService bean = combined();
        BizException rejection = assertThrows(BizException.class, bean::limitedAndBroken);
        assertThat(rejection.getErrorCode()).isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS);
        assertThat(this.harness.breakerPort.resources).isEmpty();
    }

    /** {@code OR2}：熔断的拒绝不回头让限流再问一次许可，否则一次下游故障白烧掉多个许可。 */
    @Test
    void should_not_let_a_breaker_refusal_burn_more_rate_limit_permits() {
        this.harness.breakerPort.grantCalls = 0;
        ResilienceFixtures.CombinedService bean = combined();
        BizException refusal = assertThrows(BizException.class, bean::limitedAndBroken);
        assertThat(refusal.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_DEGRADED);
        assertThat(this.harness.port.calls).hasSize(1);
    }

    /**
     * {@code OR3}：注解值与公开常量各写一次，只有一处生效时顺序表就悄悄错了。
     *
     * <p>断的是<b>关系</b>（严格小于）加上两个各自的等式：只断"值是多少"的话，把两个常量一起改成
     * {@code 0} 与 {@code 1} 也能全绿，而内外关系正好反了。
     */
    @Test
    void should_declare_the_order_once_in_the_annotation_and_once_in_the_constant_and_keep_them_equal() {
        int rateLimitOrder = orderOf(RateLimitAspect.class);
        int circuitBreakerOrder = orderOf(CircuitBreakerAspect.class);
        assertThat(rateLimitOrder).isEqualTo(-5).isEqualTo(RateLimitAspect.ASPECT_ORDER);
        assertThat(circuitBreakerOrder).isEqualTo(-4).isEqualTo(CircuitBreakerAspect.ASPECT_ORDER);
        assertThat(circuitBreakerOrder).isGreaterThan(rateLimitOrder);
    }

    /**
     * {@code OR4}：限流的重试只重试"拿许可"，绝不重试整次业务调用。
     *
     * <p>这一格防的是最顺手的一种错法：把 {@code proceed()} 写进重试循环里，于是同一次请求打下游三次、
     * 失败率还被记两笔。读数是两个计数各就各位。
     */
    @Test
    void should_retry_the_permit_acquisition_only_never_the_whole_business_call() {
        this.harness.port.grantAfter(2);
        this.harness.breakerPort.grantCalls = 0;
        assertThat(combined().retryThenBreak()).isEqualTo("safe");
        assertThat(this.harness.port.calls).hasSize(3);
        assertThat(this.harness.breakerPort.resources).hasSize(1);
        List<Long> intervals = this.harness.sleeper.intervals;
        assertThat(intervals).hasSize(2);
    }

    private static int orderOf(Class<?> aspect) {
        Order order = aspect.getAnnotation(Order.class);
        assertThat(order).as(aspect.getSimpleName() + " 类上没有 @Order，顺序由容器缺省决定").isNotNull();
        return order.value();
    }
}
