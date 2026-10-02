package org.deep361.myddd.limiter.sentinel.config;

import java.nio.file.Path;

import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.deep361.myddd.limiter.sentinel.circuit.SentinelCircuitBreakerPort;
import org.deep361.myddd.limiter.sentinel.rate.SentinelRateLimitPort;
import org.deep361.myddd.limiter.sentinel.rule.MydddSentinelRuleDocument;
import org.deep361.myddd.limiter.sentinel.rule.SentinelRuleDocumentReader;
import org.deep361.myddd.limiter.sentinel.rule.SentinelRulePublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import com.alibaba.csp.sentinel.SphO;

/**
 * Sentinel provider 的唯一一份自动装配：两个端口 bean + 一个规则下发 bean。
 *
 * <p><b>装配判定只有两个注解，各管一件事</b>：{@code @ConditionalOnClass(SphO.class)} 管"厂商在不在"，
 * {@code @Conditional(MydddLimiterProviderCondition.class)} 管"该不该是我们"（{@code enabled} 与
 * {@code myddd.limiter.provider} 两键一起判）。厂商类判定用 {@code SphO} 而不是两个端口的实现类：
 * 后者一定在本模块的 jar 里，用它等于恒真。
 *
 * <p><b>仲裁在这里，不在装配壳</b>：Task 44 的 {@code AC7} 钉过"壳不仲裁重复的端口 bean"，
 * Spring 的 {@code NoUniqueBeanDefinitionException} 要原样透出。本 provider 用
 * {@code @ConditionalOnMissingBean} 让位——业务自己交一个 {@code RateLimitPort} 实现
 * （测试替身、公司内部的统一封装）时不该被 provider 顶掉。两条仲裁各管一段且互不重叠：
 * <b>两家 provider</b> 之间的仲裁是 {@code myddd.limiter.provider}（D-150，两家都在而键没写就拒绝启动），
 * <b>provider 与业务 bean</b> 之间的仲裁是这一句让位。
 *
 * <p><b>bean 名逐字可用</b>：{@code mydddRateLimitPort}、{@code mydddCircuitBreakerPort}、
 * {@code mydddSentinelRulePublisher}。排障时要能按名字点名（{@code AC1} 钉这三串），
 * 与 Task 41 的 {@code mydddJobPort}/{@code mydddJobScanner} 同一套命名。
 *
 * <p><b>{@code memory} 来源不发布规则</b>，这是本类唯一一处"什么都不做"的分支，理由必须写出来：
 * {@code publish(EMPTY)} 走的是 {@code loadRules(空表)}，那会把<b>别的组件</b>（配置中心适配器、
 * 手工接入的 {@code DataSource} 规则源）已经下发的规则清掉。缺省来源不该有破坏性，
 * 所以 {@code memory} 只在 {@link #mydddSentinelRulePublisher} 里交出一个"来源为空文档"的 publisher，
 * 启动期一次下发都不做（{@code AC3} 钉这一格，且它同时是"reload 才有副作用"的证据）。
 */
@AutoConfiguration
@ConditionalOnClass(SphO.class)
@Conditional(MydddLimiterProviderCondition.class)
@EnableConfigurationProperties(MydddLimiterSentinelProperties.class)
public class MydddLimiterSentinelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RateLimitPort mydddRateLimitPort() {
        return new SentinelRateLimitPort();
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerPort mydddCircuitBreakerPort() {
        return new SentinelCircuitBreakerPort();
    }

    /**
     * 规则下发 bean。{@code file} 来源在构造完就 {@code reload()} 一次：配错的文件必须在启动期响，
     * 而不是等到第一次流量进来才发现"那张表其实是空的"（{@code AC5}）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SentinelRulePublisher mydddSentinelRulePublisher(MydddLimiterSentinelProperties properties) {
        SentinelRuleDocumentReader reader = new SentinelRuleDocumentReader();
        if (MydddLimiterSentinelProperties.SOURCE_FILE.equals(properties.getRuleSource())) {
            Path location = properties.resolvedRuleLocation();
            SentinelRulePublisher publisher = new SentinelRulePublisher(() -> reader.read(location));
            publisher.reload();
            return publisher;
        }
        return new SentinelRulePublisher(() -> MydddSentinelRuleDocument.EMPTY);
    }
}
