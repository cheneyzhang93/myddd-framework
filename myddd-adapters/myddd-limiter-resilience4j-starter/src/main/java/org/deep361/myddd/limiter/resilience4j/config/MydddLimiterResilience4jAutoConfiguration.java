package org.deep361.myddd.limiter.resilience4j.config;

import java.time.Duration;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.limiter.resilience4j.circuit.Resilience4jCircuitBreakerPort;
import org.deep361.myddd.limiter.resilience4j.rate.Resilience4jRateLimitPort;
import org.deep361.myddd.limiter.resilience4j.rule.Resilience4jRuleDocumentReader;
import org.deep361.myddd.limiter.resilience4j.rule.Resilience4jRulePublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

/**
 * Resilience4j provider 的唯一一份自动装配（§0.3-6）。五个 bean：两张注册表、读取器、publisher、两个端口。
 *
 * <p><b>注册表的缺省配置在这里被换成"绝不可能被用到的那种"</b>：{@code RateLimiterRegistry.ofDefaults()}
 * 的缺省是 {@code limitForPeriod=50 / refresh=500ns / timeout=PT5S}（实测 {@code Probe46d_5}），
 * 而端口只走 {@code find(name)}——名字不在场就放行且不建实例（{@code Step 6}）。所以这里的缺省配置
 * 在本模块<b>一行都不会被读到</b>，摆一份"不可阻塞"的值只是防止业务自己拿 {@code registry.rateLimiter(name)}
 * 绕过 publisher 时踩到 PT5S：那是一条我们把不了的口子，能堵则堵，并在这里写明它为什么是死的。
 *
 * <p><b>{@code rule-source=file} 的装载时机</b>：publisher bean 上的 {@code initMethod} 不用，
 * 改成显式 {@code @Bean} 里调用 {@code reload()}——与 Task 45 同一条裁定：装配期报错比运行期第一条请求
 * 报错更早，且失败信息落在 bean 名上。
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.github.resilience4j.ratelimiter.RateLimiter")
@Conditional(MydddLimiterProviderCondition.class)
@EnableConfigurationProperties(MydddLimiterResilience4jProperties.class)
public class MydddLimiterResilience4jAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofDays(3650))
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
    }

    @Bean
    @ConditionalOnMissingBean
    public Resilience4jRuleDocumentReader resilience4jRuleDocumentReader(
            MydddLimiterResilience4jProperties properties) {
        return new Resilience4jRuleDocumentReader(properties.resolvedRuleLocation());
    }

    @Bean
    @ConditionalOnMissingBean
    public Resilience4jRulePublisher resilience4jRulePublisher(RateLimiterRegistry rateLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry, Resilience4jRuleDocumentReader reader,
            MydddLimiterResilience4jProperties properties) {
        Resilience4jRulePublisher publisher =
                new Resilience4jRulePublisher(rateLimiterRegistry, circuitBreakerRegistry, reader);
        if (MydddLimiterResilience4jProperties.SOURCE_FILE.equals(properties.getRuleSource())) {
            int loaded = publisher.reload();
            if (loaded == 0) {
                throw new FrameworkException("myddd.limiter.resilience4j.rule-source=file 指向的文件解析出 0 条规则："
                        + "两段都存在但都是空数组。空表让所有资源全放行，与"
                        + "「配了规则文件」的意图相反 ⇒ 要么删掉这一键，要么写进真规则");
            }
        }
        return publisher;
    }

    @Bean
    @ConditionalOnMissingBean
    public Resilience4jRateLimitPort resilience4jRateLimitPort(RateLimiterRegistry registry) {
        return new Resilience4jRateLimitPort(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public Resilience4jCircuitBreakerPort resilience4jCircuitBreakerPort(CircuitBreakerRegistry registry) {
        return new Resilience4jCircuitBreakerPort(registry);
    }
}
