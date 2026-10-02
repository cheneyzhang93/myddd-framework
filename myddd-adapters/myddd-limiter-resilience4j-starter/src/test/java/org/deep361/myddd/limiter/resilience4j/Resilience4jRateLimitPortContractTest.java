package org.deep361.myddd.limiter.resilience4j;

import java.time.Duration;

import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.deep361.myddd.limiter.resilience4j.rate.Resilience4jRateLimitPort;
import org.deep361.myddd.testing.contract.RateLimitPortContractTestBase;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

/**
 * {@code RL-C1}–{@code RL-C6} 在 Resilience4j 上的一遍。
 *
 * <p><b>{@code configure} 与 {@code awaitFreshWindow} 走同一条路线：删掉再建</b>。r4j 的额度是按
 * {@code limitRefreshPeriod} 周期补充的令牌桶，而基类的 {@code WINDOW=10s} 意味着"真等"要睡满 10 秒；
 * 基类 javadoc 允许的第二条路（重建实例）在这里是零成本的：{@code remove} 对不存在的名字不抛
 * （{@code Probe46f F3}），删完 {@code rateLimiter(name, cfg)} 必按新配置建（{@code Probe46g G1/G2}）。
 * ⇒ 本类 6 条用例的墙钟开销是 0 秒，Sentinel 侧同一批是 10.8 秒（对照表就在本步骤末尾）。
 *
 * <p>⚠️ 这条路线<b>不是</b>"我们比 Sentinel 快"：换实例的代价是热更新会重置熔断状态（那边是
 * {@code RS11}/{@code AB6} 用来确认额度是新的，这边是 {@code CB-C} 组顺带拿到的干净窗口）。两笔账一起交。
 */
class Resilience4jRateLimitPortContractTest extends RateLimitPortContractTestBase {

    private final RateLimiterRegistry registry = RateLimiterRegistry.of(
            RateLimiterConfig.custom().limitForPeriod(1)
                    .limitRefreshPeriod(Duration.ofDays(3650)).timeoutDuration(Duration.ZERO).build());

    @Override
    protected RateLimitPort rateLimitPort() {
        return new Resilience4jRateLimitPort(this.registry);
    }

    /** 满额度新实例 = 干净窗口（{@code permits} 个许可 / 基类的 {@code WINDOW}）。 */
    @Override
    protected void configure(String resource, int permits) {
        this.registry.remove(resource);
        this.registry.rateLimiter(resource, RateLimiterConfig.custom()
                .limitForPeriod(permits)
                .limitRefreshPeriod(RateLimitPortContractTestBase.WINDOW)
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    @Override
    protected void awaitFreshWindow() {
        // 对表上每一个已配资源重建一次：基类的语义是"下一次调用面对一个干净窗口"。
        this.registry.getAllRateLimiters().stream().map(RateLimiter::getName).toList()
                .forEach(name -> configure(name, this.registry.find(name)
                        .map(limiter -> limiter.getRateLimiterConfig().getLimitForPeriod()).orElse(1)));
    }
}
