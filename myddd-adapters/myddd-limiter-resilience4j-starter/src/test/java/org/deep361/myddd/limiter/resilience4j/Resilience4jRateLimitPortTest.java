package org.deep361.myddd.limiter.resilience4j;

import java.time.Duration;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.limiter.resilience4j.circuit.Resilience4jCircuitBreakerPort;
import org.deep361.myddd.limiter.resilience4j.rate.Resilience4jRateLimitPort;
import org.deep361.myddd.limiter.resilience4j.rule.MydddResilience4jRuleDocument;
import org.deep361.myddd.limiter.resilience4j.rule.Resilience4jRuleDocumentReader;
import org.deep361.myddd.limiter.resilience4j.rule.Resilience4jRulePublisher;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流端口的行为面（{@code AB1}–{@code AB6}）。
 *
 * <p>每组都用<b>新建的注册表</b>：r4j 的注册表本身就是可注入的普通对象，没有 Sentinel 那种进程级静态表
 * 要隔离（对照 Task 45 的 Step 7 三条前置）。缺省配置一律 {@code timeoutDuration=ZERO}——非阻塞是端口
 * 契约，不是这里可调的量。
 */
class Resilience4jRateLimitPortTest {

    private final RateLimiterRegistry registry = RateLimiterRegistry.of(
            RateLimiterConfig.custom().limitForPeriod(1)
                    .limitRefreshPeriod(Duration.ofDays(3650)).timeoutDuration(Duration.ZERO).build());
    private final Resilience4jRateLimitPort port = new Resilience4jRateLimitPort(this.registry);

    /** 配一份额度：1 秒刷新周期 + ZERO 超时，与 publisher 下发的形态逐字同形。 */
    private void configure(String resource, int limitForPeriod) {
        this.registry.remove(resource);
        this.registry.rateLimiter(resource, RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    /** AB1：没配规则的资源放行，<b>而且不建实例</b>——建了就是一个 50/500ns/PT5S 的桶，既不是"不限流"也是后门。 */
    @Test
    void should_grant_and_not_create_an_instance_for_an_unconfigured_resource() {
        assertTrue(this.port.tryAcquire("no-such-rule", 1));
        assertThat(this.registry.getAllRateLimiters()).isEmpty();
        assertFalse(this.registry.find("no-such-rule").isPresent());
    }

    /** AB2：批量申请的扣减语义（2 吃掉 2 个），且被拒那一发的代价是 0 阻塞而不是"看起来像 0"。 */
    @Test
    void should_charge_two_permits_and_deny_immediately_when_the_budget_is_short() {
        configure("batch", 3);
        assertTrue(this.port.tryAcquire("batch", 2));

        long start = System.nanoTime();
        assertFalse(this.port.tryAcquire("batch", 2), "只剩 1 个许可，再要 2 个必须立即拒");
        long deniedMillis = (System.nanoTime() - start) / 1_000_000L;
        // 断区间而不是断恰好 0：Probe46e E2b 的 blockedMs=0 是毫秒整数除法的读数，真值是"小于 1ms"。
        assertThat(deniedMillis).isLessThan(50L);

        assertTrue(this.port.tryAcquire("batch", 1));
        assertFalse(this.port.tryAcquire("batch", 1));
    }

    /** AB3：申请数大于整份额度 ⇒ 拒且<b>不部分扣减</b>（要么全给要么不给）。 */
    @Test
    void should_not_partially_charge_an_oversized_request() {
        configure("oversized", 3);
        int before = available("oversized");
        assertFalse(this.port.tryAcquire("oversized", 4));
        assertEquals(before, available("oversized"));
        assertEquals(before, 3);
    }

    /**
     * AB4：非法入参在碰厂商之前判掉，而且注册表里什么都没发生。
     * 这一格存在的唯一理由是厂商态度：{@code acquirePermission(0)} 与 {@code (-1)} 都返回 true 且不抛，
     * 负数还会往桶里灌额度（{@code Probe46d_1/2}、{@code Probe46e E1}：3→5→7，随后 8 发全放行）。
     */
    @Test
    void should_validate_arguments_before_touching_the_vendor() {
        configure("checked", 2);
        RateLimiter existing = this.registry.find("checked").orElseThrow();
        int availableBefore = existing.getMetrics().getAvailablePermissions();
        int sizeBefore = this.registry.getAllRateLimiters().size();

        assertThrows(IllegalArgumentException.class, () -> this.port.tryAcquire("checked", 0));
        assertThrows(IllegalArgumentException.class, () -> this.port.tryAcquire("checked", -1));
        assertThrows(IllegalArgumentException.class, () -> this.port.tryAcquire(null, 1));
        assertThrows(IllegalArgumentException.class, () -> this.port.tryAcquire("  ", 1));

        assertThat(this.registry.getAllRateLimiters()).hasSize(sizeBefore);
        assertEquals(availableBefore, existing.getMetrics().getAvailablePermissions());
    }

    /**
     * AB5：两张注册表互不相干（{@code Probe46f F8}/{@code F8b}）——两个方向各写一次，单向只钉了一半。
     * 这也是本模块不需要资源名前缀的行为面证据。
     */
    @Test
    void should_keep_the_two_registries_independent_in_both_directions() {
        CircuitBreakerRegistry circuitRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        CircuitBreaker breaker = circuitRegistry.circuitBreaker("shared", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50F)
                .waitDurationInOpenState(Duration.ofSeconds(30)).build());
        Resilience4jCircuitBreakerPort circuitPort = new Resilience4jCircuitBreakerPort(circuitRegistry);

        configure("shared", 1);
        assertTrue(this.port.tryAcquire("shared", 1));
        assertFalse(this.port.tryAcquire("shared", 1));
        // 方向一：限流额度耗尽，熔断端口照常执行动作（前缀不存在 ⇒ 同一个名字互不干扰）。
        assertEquals("ok", circuitPort.execute("shared", () -> "ok"));

        // 一发失败就把熔断打到 OPEN（窗口 2、minCalls 2、阈值 50%）。
        assertThrows(IllegalStateException.class, () -> circuitPort.execute("shared", () -> {
            throw new IllegalStateException("下游超时");
        }));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // 方向二：熔断开闸，限流端口仍按自己的额度给答案。
        configure("shared", 2);
        assertTrue(this.port.tryAcquire("shared", 1));
        assertTrue(this.port.tryAcquire("shared", 1));
        assertFalse(this.port.tryAcquire("shared", 1));
        assertThrows(BizException.class, () -> circuitPort.execute("shared", () -> "never"));
    }

    /** AB6：热更新在端口层看就是"立刻按新额度的满桶"（publisher 层看实例身份的那条是 RS11）。 */
    @Test
    void should_see_the_new_full_budget_right_after_a_republish() {
        Resilience4jRulePublisher publisher = new Resilience4jRulePublisher(this.registry,
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()),
                new Resilience4jRuleDocumentReader(null));
        publisher.publish(flowDocument("hot", 1));
        assertTrue(this.port.tryAcquire("hot", 1));
        assertFalse(this.port.tryAcquire("hot", 1));

        publisher.publish(flowDocument("hot", 9));
        assertTrue(this.port.tryAcquire("hot", 9));
        assertFalse(this.port.tryAcquire("hot", 1));
    }

    private static MydddResilience4jRuleDocument flowDocument(String resource, int count) {
        MydddResilience4jRuleDocument document = new MydddResilience4jRuleDocument();
        MydddResilience4jRuleDocument.FlowEntry entry = new MydddResilience4jRuleDocument.FlowEntry();
        entry.setResource(resource);
        entry.setCount(count);
        document.getFlow().add(entry);
        return document;
    }

    private int available(String resource) {
        return this.registry.find(resource).orElseThrow().getMetrics().getAvailablePermissions();
    }
}
