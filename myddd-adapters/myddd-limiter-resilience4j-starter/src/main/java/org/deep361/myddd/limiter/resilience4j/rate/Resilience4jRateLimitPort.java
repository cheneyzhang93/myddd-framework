package org.deep361.myddd.limiter.resilience4j.rate;

import java.util.Objects;
import java.util.Optional;

import org.deep361.myddd.kernel.port.resilience.RateLimitPort;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

/**
 * {@link RateLimitPort} 的 Resilience4j 实现：一次 {@code acquirePermission(permits)}，拿不到就返回 false。
 *
 * <p><b>为什么这里没有资源名前缀</b>（对照 Sentinel 侧的 D-154）：r4j 的限流与熔断是<b>两张不同类型的
 * 注册表</b>（{@code RateLimiterRegistry} / {@code CircuitBreakerRegistry}），同一个名字写进两张表互不相干
 * （实测 {@code Probe46f F8}：两张表各放一个 {@code shared} ⇒ 限流侧 {@code true,false}、熔断侧 {@code OPEN}，
 * 反向删掉限流那一份后熔断仍 {@code OPEN}）。Sentinel 侧必须加前缀是因为那里全局只有一张资源表。
 *
 * <p><b>非阻塞靠配置而不是靠方法名</b>：{@code RateLimiter} 上根本没有 {@code tryAcquirePermission} 家族
 * （javap 2.4.0 全量方法表），能给布尔的只有 {@code acquirePermission(int)}，而它是否阻塞由
 * {@code timeoutDuration} 决定——同一发超大请求 {@code timeout=5s} 阻塞 1002ms、{@code timeout=ZERO} 阻塞 0ms
 * （{@code Probe46e E2/E2b}）。⇒ 额度只能由 {@code Resilience4jRulePublisher} 下发（它把 ZERO 钉成常量），
 * 本类不碰配置。
 *
 * <p><b>没有规则的资源一律放行且不建实例</b>：走 {@code registry.find(name)} 而不是
 * {@code registry.rateLimiter(name)}，后者会用注册表缺省配置<b>当场造一个</b>
 * {@code limitForPeriod=50 / refresh=500ns / timeout=PT5S} 的实例（{@code Probe46d_5}）——那既不是"不限流"
 * 也是一条能阻塞 5 秒的后门。{@code find} 不创建（{@code F1}：调用之后 {@code getAllRateLimiters()} 仍是 0）。
 * 这一条与 Sentinel 侧"没有流控规则 ⇒ 全放行"同语义，两家因此可等价。
 */
public class Resilience4jRateLimitPort implements RateLimitPort {

    private final RateLimiterRegistry registry;

    public Resilience4jRateLimitPort(RateLimiterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public boolean tryAcquire(String resource, int permits) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource 不能为空（r4j 注册表按名字索引，空名资源共享一个桶）");
        }
        if (permits <= 0) {
            // 厂商对这两个值的态度是"返回 true 并且往桶里补许可"（Probe46d_1/2 不抛、Probe46e E1：
            // limit=2 的规则上三次 acquirePermission(-1) 之后 avail 3→5→7、随后 8 发全放行）⇒ 只能在这里拦。
            throw new IllegalArgumentException("permits 必须是正数，实际 " + permits
                    + "：r4j 不但不拒，负数还会增加该资源的剩余许可");
        }
        Optional<RateLimiter> configured = registry.find(resource);
        if (configured.isEmpty()) {
            return true;
        }
        return configured.get().acquirePermission(permits);
    }
}
