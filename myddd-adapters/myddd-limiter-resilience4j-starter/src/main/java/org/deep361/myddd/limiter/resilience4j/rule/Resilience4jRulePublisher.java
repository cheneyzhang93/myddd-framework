package org.deep361.myddd.limiter.resilience4j.rule;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.deep361.myddd.kernel.common.error.FrameworkException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

/**
 * 规则面的<b>唯一</b>写入口：把文档模型整张表换成两张注册表里的实例，并回读核验。
 *
 * <p><b>每个资源两步：先 {@code registry.remove(name)} 再 {@code registry.xxx(name, config)}</b>。
 * 这不是风格选择，是三条实测把别的路全堵死的结果：
 * <ol>
 *   <li>{@code rateLimiter(name, config)} 对<b>已存在</b>的名字返回同一实例并忽略新配置
 *       （{@code Probe46 E}：改名后再要一次 ⇒ {@code secondLimit=1}）；</li>
 *   <li>{@code changeLimitForPeriod(n)} 改了额度<b>不补</b>桶（{@code D}：{@code avail} 0→0、{@code grant5=false}）；</li>
 *   <li>{@code registry.replace(name, instance)} 只在名字已存在时生效，名字不在时是<b>静默 no-op</b>
 *       （{@code Probe46f F2}：{@code previous=false}、{@code find} 仍空、{@code size} 仍 0，不报错不日志）。</li>
 * </ol>
 * 而 {@code remove} 对不存在的名字返回 {@code empty} 且不抛（{@code F3}），删完再建必然按新配置落地
 * （{@code Probe46g G1/G2}：额度耗尽后重发 {@code 9} ⇒ 立刻按 9 放行、{@code sameInstanceAsOld=false}）。
 * 一步"删"和一步"建"因此对<b>所有</b>资源同形，不需要 {@code if (present)} 分支——那个分支正是 {@code F2}
 * 会咬人的地方。
 *
 * <p><b>副作用要写在这里而不是藏在测试里</b>：重发会把处于 {@code OPEN} 的熔断打回 {@code CLOSED}
 * （{@code F6}/{@code G3b}）。这既让本模块的契约基类零睡眠成立（{@code RS} 组的
 * {@code RL-C}/{@code CB-C} 的 {@code configure()} 就是这一句），也是一条运维语义：<b>推一次规则等于给所有
 * 资源各一次重新试探的机会</b>。
 *
 * <p><b>回读核验比 Sentinel 侧多一条</b>：那里厂商静默丢规则 ⇒ 比对"条数与名字"；这里厂商会自己抛 ⇒
 * 比对的目的是"我们的两步路线有没有真的换掉实例"，因此除了名字集还要比 {@code limitForPeriod} 与
 * {@code slidingWindowSize}/{@code failureRateThreshold} 这些<b>配置值</b>（{@code G4/G5} 那两格读的就是它们）。
 */
public class Resilience4jRulePublisher {

    /** 文档只给"每秒多少个"这一格 ⇒ 刷新周期是框架常量，不是键。 */
    static final Duration REFRESH_PERIOD = Duration.ofSeconds(1);
    /** 端口 javadoc 承诺非阻塞：唯一的落点就是这一句（{@code Probe46e E2/E2b}：5s ⇒ 阻塞 1002ms、0 ⇒ 0ms）。 */
    static final Duration TIMEOUT = Duration.ZERO;

    private final RateLimiterRegistry rateLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Resilience4jRuleDocumentReader reader;

    public Resilience4jRulePublisher(RateLimiterRegistry rateLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry, Resilience4jRuleDocumentReader reader) {
        this.rateLimiterRegistry = Objects.requireNonNull(rateLimiterRegistry, "rateLimiterRegistry");
        this.circuitBreakerRegistry = Objects.requireNonNull(circuitBreakerRegistry, "circuitBreakerRegistry");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** 按 {@code myddd.limiter.resilience4j.rule-source} 装载（{@code memory} ⇒ 空表）。 */
    public int reload() {
        return publish(reader.read());
    }

    /** 整张表替换：下发、清除表外旧资源、回读核验。返回下发条数（flow + degrade）。 */
    public int publish(MydddResilience4jRuleDocument document) {
        Resilience4jRuleValidator.validate(document);
        Set<String> kept = new LinkedHashSet<>();
        for (int i = 0; i < document.getFlow().size(); i++) {
            MydddResilience4jRuleDocument.FlowEntry entry = document.getFlow().get(i);
            String resource = entry.getResource();
            publishFlow(where("flow", i, resource), requireName(resource), entry.getCount());
            kept.add(resource);
        }
        for (int i = 0; i < document.getDegrade().size(); i++) {
            MydddResilience4jRuleDocument.DegradeEntry entry = document.getDegrade().get(i);
            String resource = entry.getResource();
            publishDegrade(where("degrade", i, resource), requireName(resource), entry);
            kept.add(resource);
        }
        removeStale(kept);
        int total = document.getFlow().size() + document.getDegrade().size();
        verifyLoaded(document);
        return total;
    }

    /**
     * 厂商 {@code build()} 的越界读数翻译成 {@code FrameworkException} 并补上位置。
     *
     * <p><b>这里一条数值区间都不查</b>：{@code limitForPeriod<=0} 的真源是厂商那一句
     * {@code IllegalArgumentException: LimitForPeriod should be greater than 0}（一手实测
     * {@code ProbeR4jQuota} 的 A/B/C 三格），在框架里再抄一遍阈值就是第二个真源。翻译只补两样
     * 厂商给不了的东西：是哪一段哪一条（{@code flow[0]（资源 …）}）和我们统一的错误面——
     * 换 provider 时"坏规则文件在启动期响的是同一个异常类型"这条关系必须成立，而不是
     * Sentinel 侧响 {@code FrameworkException}、这一侧响厂商的英文异常。
     */
    private static void buildInto(String where, Runnable vendorCall) {
        try {
            vendorCall.run();
        } catch (IllegalArgumentException refusedByVendor) {
            throw new FrameworkException(where + " 被厂商拒绝：" + refusedByVendor.getMessage()
                    + "。判据在厂商，这里只补位置与异常类型；框架不重复实现一套阈值", refusedByVendor);
        }
    }

    private static String where(String section, int index, String resource) {
        return section + "[" + index + "]（资源 " + resource + "）";
    }

    private void publishFlow(String where, String name, Integer count) {
        buildInto(where, () -> {
            rateLimiterRegistry.remove(name);
            rateLimiterRegistry.rateLimiter(name, RateLimiterConfig.custom()
                    .limitForPeriod(count)
                    .limitRefreshPeriod(REFRESH_PERIOD)
                    .timeoutDuration(TIMEOUT)
                    .build());
        });
    }

    private void publishDegrade(String where, String name, MydddResilience4jRuleDocument.DegradeEntry entry) {
        buildInto(where, () -> {
            long windowMs = entry.getWindowMs() == null ? 1000L : entry.getWindowMs();
            CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                    .slidingWindowSize((int) (windowMs / 1000L))
                    .failureRateThreshold(entry.getRatio())
                    .waitDurationInOpenState(Duration.ofSeconds(entry.getOpenSeconds() == null
                            ? 30L : entry.getOpenSeconds()));
            if (entry.getMinCalls() != null) {
                builder.minimumNumberOfCalls(entry.getMinCalls());
            }
            if (Resilience4jRuleValidator.SLOW_RATIO.equals(entry.getThreshold())) {
                builder.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                        .slowCallRateThreshold(entry.getRatio())
                        .slowCallDurationThreshold(Duration.ofMillis(entry.getMaxRtMs()));
            }
            circuitBreakerRegistry.remove(name);
            circuitBreakerRegistry.circuitBreaker(name, builder.build());
        });
    }

    /** 表外资源必须消失：否则删掉一条规则在 r4j 侧等于"没删"（旧实例带着旧额度继续拦）。 */
    private void removeStale(Set<String> kept) {
        Set<String> rates = new LinkedHashSet<>();
        rateLimiterRegistry.getAllRateLimiters().forEach(limiter -> rates.add(limiter.getName()));
        rates.forEach(name -> {
            if (!kept.contains(name)) {
                rateLimiterRegistry.remove(name);
            }
        });
        Set<String> breaks = new LinkedHashSet<>();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(breaker -> breaks.add(breaker.getName()));
        breaks.forEach(name -> {
            if (!kept.contains(name)) {
                circuitBreakerRegistry.remove(name);
            }
        });
    }

    private static String requireName(String resource) {
        if (resource == null || resource.isBlank()) {
            throw new FrameworkException("资源名不能为空：r4j 的注册表按名字索引，空串会让所有空名资源共享一个桶，"
                    + "而这条在厂商侧没有任何报错");
        }
        return resource;
    }

    private void verifyLoaded(MydddResilience4jRuleDocument document) {
        // 同资源重复时厂商语义是"后写的那条覆盖前一条"（先 remove 再建，逐条同形），
        // 因此回读必须对着"每条资源的最终额度"比，而不是对着文档行数比（RS7：重复不许报错）。
        Map<String, Integer> expectedLimits = new LinkedHashMap<>();
        document.getFlow().forEach(entry -> expectedLimits.put(entry.getResource(), entry.getCount()));
        Set<String> expectedFlow = new LinkedHashSet<>(expectedLimits.keySet());
        Set<String> actualFlow = new LinkedHashSet<>();
        rateLimiterRegistry.getAllRateLimiters().forEach(limiter -> actualFlow.add(limiter.getName()));
        if (!expectedFlow.equals(actualFlow)) {
            throw new FrameworkException("Resilience4j 限流注册表回读的名字集与下发表不一致：缺少 "
                    + minus(expectedFlow, actualFlow) + "，多出 " + minus(actualFlow, expectedFlow)
                    + "。这里厂商不会静默丢规则（build() 越界值当场抛），所以不一致只可能是"
                    + "「先 remove 再建」这条路线被改坏（见类 javadoc 的三条实测）");
        }
        for (Map.Entry<String, Integer> expected : expectedLimits.entrySet()) {
            RateLimiter limiter = rateLimiterRegistry.find(expected.getKey())
                    .orElseThrow(() -> new FrameworkException("回读时资源 " + expected.getKey() + " 不见了"));
            if (limiter.getRateLimiterConfig().getLimitForPeriod() != expected.getValue()
                    || !Duration.ZERO.equals(limiter.getRateLimiterConfig().getTimeoutDuration())) {
                throw new FrameworkException("Resilience4j 资源 " + expected.getKey() + " 的回读配置与下发不符："
                        + "limitForPeriod 期望 " + expected.getValue() + " 实际 "
                        + limiter.getRateLimiterConfig().getLimitForPeriod() + "，timeoutDuration 实际 "
                        + limiter.getRateLimiterConfig().getTimeoutDuration()
                        + "。timeoutDuration 必须是 ZERO——它是端口非阻塞承诺的唯一落点");
            }
        }
        Set<String> expectedDegrade = new LinkedHashSet<>();
        document.getDegrade().forEach(entry -> expectedDegrade.add(entry.getResource()));
        Set<String> actualDegrade = new LinkedHashSet<>();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(breaker -> actualDegrade.add(breaker.getName()));
        if (!expectedDegrade.equals(actualDegrade)) {
            throw new FrameworkException("Resilience4j 熔断注册表回读的名字集与下发表不一致：缺少 "
                    + minus(expectedDegrade, actualDegrade) + "，多出 " + minus(actualDegrade, expectedDegrade));
        }
    }

    private static Set<String> minus(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }
}
