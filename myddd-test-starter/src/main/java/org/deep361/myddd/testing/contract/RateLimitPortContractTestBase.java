package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RateLimitPort} 的跨厂商契约：Sentinel 与 Resilience4j 必须在"额度、消耗、恢复"三件事上同形。
 *
 * <p>{@link #configure(String, int)} 是规则下发缝：本计划把两家的规则都写成"一个窗口 {@code permits} 个令牌"
 * （Resilience4j 侧 = {@code limitForPeriod=permits} + {@code limitRefreshPeriod} 取窗口宽度 +
 * {@code timeoutDuration=ZERO}；Sentinel 侧 = {@code Rule.count=permits}、GRADE=THREAD/QPS），
 * 所以基类才可能用同一句"第 N 次申请"断言两边。
 */
public abstract class RateLimitPortContractTestBase {

    /** 一个窗口的宽度：两个 provider 都必须按这个值配规则（limiter 等价性夹具用它）。 */
    protected static final Duration WINDOW = Duration.ofSeconds(10);

    /** 被测端口。 */
    protected abstract RateLimitPort rateLimitPort();

    /** 下发规则：资源 {@code resource} 在一个窗口内允许 {@code permits} 次通过。 */
    protected abstract void configure(String resource, int permits);

    /** 让额度重新可用（真等窗口翻篇，或重建实例——两种实现都必须让 RL-C5 成立）。 */
    protected abstract void awaitFreshWindow();

    /** RL-C1：额度内申请通过。 */
    @Test
    void should_grant_requests_inside_the_budget() {
        configure("rl-order-create", 2);
        assertTrue(rateLimitPort().tryAcquire("rl-order-create", 1));
        assertTrue(rateLimitPort().tryAcquire("rl-order-create", 1));
    }

    /** RL-C2：超出额度立即拒绝，不阻塞（R8 的有界等待发生在切面，不在端口）。 */
    @Test
    void should_deny_requests_beyond_the_budget() {
        configure("rl-order-create", 2);
        assertTrue(rateLimitPort().tryAcquire("rl-order-create", 1));
        assertTrue(rateLimitPort().tryAcquire("rl-order-create", 1));
        assertFalse(rateLimitPort().tryAcquire("rl-order-create", 1));
    }

    /** RL-C3：一次申请多个令牌必须真吃掉多个，而不是只扣 1。 */
    @Test
    void should_charge_every_permit_requested() {
        configure("rl-batch-import", 3);
        assertTrue(rateLimitPort().tryAcquire("rl-batch-import", 2));
        assertFalse(rateLimitPort().tryAcquire("rl-batch-import", 2),
                "只剩 1 个令牌，再要 2 个必须失败");
    }

    /** RL-C4：资源之间互不影响。 */
    @Test
    void should_keep_resource_budgets_independent() {
        configure("rl-a", 1);
        configure("rl-b", 1);
        assertTrue(rateLimitPort().tryAcquire("rl-a", 1));
        assertFalse(rateLimitPort().tryAcquire("rl-a", 1));
        assertTrue(rateLimitPort().tryAcquire("rl-b", 1));
    }

    /** RL-C5：窗口翻篇后额度恢复——限流不是永久封禁。 */
    @Test
    void should_restore_the_budget_after_the_window_rolls_over() {
        configure("rl-order-create", 1);
        assertTrue(rateLimitPort().tryAcquire("rl-order-create", 1));
        assertFalse(rateLimitPort().tryAcquire("rl-order-create", 1));
        awaitFreshWindow();
        assertTrue(rateLimitPort().tryAcquire("rl-order-create", 1));
    }

    /**
     * RL-C6：非法入参快速失败（端口层不做"猜意图"），且校验先于规则查询——所以每一条都发生在
     * "规则已下发之后"这个前提下也必须抛，而不是命中"规则里没有这条资源"那条退路。
     */
    @Test
    void should_reject_illegal_arguments() {
        assertThrows(IllegalArgumentException.class, () -> rateLimitPort().tryAcquire("  ", 1));
        assertThrows(IllegalArgumentException.class, () -> rateLimitPort().tryAcquire(null, 1));
        assertThrows(IllegalArgumentException.class, () -> {
            configure("rl-order-create", 1);
            rateLimitPort().tryAcquire("rl-order-create", 0);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            configure("rl-order-create", 1);
            rateLimitPort().tryAcquire("rl-order-create", -1);
        });
    }
}
