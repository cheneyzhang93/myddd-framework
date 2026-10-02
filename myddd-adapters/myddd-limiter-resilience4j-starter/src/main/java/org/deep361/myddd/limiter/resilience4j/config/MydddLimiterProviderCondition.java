package org.deep361.myddd.limiter.resilience4j.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 只在"本模块该装配"时放行的条件：{@code myddd.limiter.resilience4j.enabled} 与
 * {@code myddd.limiter.provider} 两键一起判。
 *
 * <p>{@code @ConditionalOnProperty} 不是 {@code @Repeatable}（Plan 3 一手实测），所以"总开关 +
 * 选中标识"没法用两个注解叠在同一类型上——自定义 {@link Condition} 是唯一能同时判两键又不引入
 * 第二份自动配置的做法（§0.3-6：一个模块一份自动配置）。
 */
public class MydddLimiterProviderCondition implements Condition {

    /** provider 标识 → marker 类名（<b>字符串</b>，不是依赖，§0.3-11）。 */
    private static final Map<String, String> MARKERS = Map.of(
            "sentinel",
            "org.deep361.myddd.limiter.sentinel.rate.SentinelRateLimitPort",
            LimiterProviderSelection.RESILIENCE4J,
            "org.deep361.myddd.limiter.resilience4j.rate.Resilience4jRateLimitPort");

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (!environment.getProperty("myddd.limiter.resilience4j.enabled", boolean.class, true)) {
            return false;
        }
        return LimiterProviderSelection.shouldAssembleResilience4j(
                environment.getProperty(LimiterProviderSelection.KEY), presentProviders(context));
    }

    /**
     * classpath 上有哪些 provider。用 {@code Class.forName(name, false, loader)}——<b>不初始化</b>：
     * 探"在不在场"不该触发对方的静态块。读口名是 {@code getClassLoader()}：spring-context 7.0.9 的
     * {@code ConditionContext} 只有五个方法，没有 {@code getBeanClassLoader}（偏差 D-138）。
     */
    private Set<String> presentProviders(ConditionContext context) {
        ClassLoader classLoader = context.getClassLoader() == null
                ? getClass().getClassLoader() : context.getClassLoader();
        Set<String> present = new TreeSet<>();
        MARKERS.forEach((provider, markerClass) -> {
            try {
                Class.forName(markerClass, false, classLoader);
                present.add(provider);
            } catch (Throwable absent) {
                // 探不到就是不在场。"类不存在"与"链接失败"在这里等价：该 provider 不可用。
            }
        });
        return present;
    }
}
