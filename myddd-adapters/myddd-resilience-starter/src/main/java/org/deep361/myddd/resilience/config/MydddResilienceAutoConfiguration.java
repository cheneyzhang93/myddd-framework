package org.deep361.myddd.resilience.config;

import org.aspectj.lang.Aspects;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.deep361.myddd.resilience.CircuitBreakerAspect;
import org.deep361.myddd.resilience.PollSleeper;
import org.deep361.myddd.resilience.RateLimitAspect;
import org.deep361.myddd.resilience.ThreadPollSleeper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * {@code @RateLimit} 与 {@code @CircuitBreaker} 的装配：交出<b>两个切面 bean</b>，别的一个都不产。
 *
 * <p><b>条件与 Plan 3 的锁切面装配逐字同形</b>（{@code MydddConsistencyLockAopAutoConfiguration}）：
 * {@code @ConditionalOnClass(Aspects.class)} + 总开关 {@code myddd.resilience.enabled}（缺省开）；
 * <b>不</b>写 {@code @ConditionalOnBean(RateLimitPort.class)}——端口有没有在<b>第一次调用</b>时给诊断
 * （{@code RL14}），启动期靠 {@code @ConditionalOnBean} 猜装配顺序在 {@code ApplicationContextRunner}
 * 下测不到（Plan 3 的同一课）。也<b>不</b>写 {@code @ConditionalOnMissingBean}：切面不是替换点，
 * 替换点是两个端口。框架一律不声明 {@code @EnableAspectJAutoProxy}——开不开代理是应用的决定。
 *
 * <p><b>包名用 {@code config} 而 Plan 3 用 {@code autoconfigure}</b>：本计划的八个 provider/壳模块全部落在
 * {@code <能力>.config} 下（Task 33 的 mq、Task 35 的 rocketmq、Task 41/42 的 job），统一比追平历史更省事；
 * 这条不对称登记为偏差 D-147，Task 59 的 README 里写一行"壳与 provider 的装配类都在 {@code config} 包"。
 *
 * <p><b>{@link PollSleeper} 不作为 bean 暴露</b>：它是切面的内部件，与 Task 41 对
 * {@code ThreadPoolTaskScheduler} 的裁定同一条理由——占一个 bean 名就等于给应用一个"替换它"的期待，
 * 而换掉等待节拍会同时改变 {@code RL} 组全部判据的可观察面。要测等待形状就走构造器（夹具正是这么做的）。
 */
@AutoConfiguration
@ConditionalOnClass(Aspects.class)
@EnableConfigurationProperties(MydddResilienceProperties.class)
@ConditionalOnProperty(prefix = "myddd.resilience", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class MydddResilienceAutoConfiguration {

    @Bean
    RateLimitAspect mydddRateLimitAspect(ObjectProvider<RateLimitPort> rateLimitPorts,
            MydddResilienceProperties properties) {
        return new RateLimitAspect(rateLimitPorts, properties.getRateLimit().getTimeoutMaxBusyWait().toMillis(),
                new ThreadPollSleeper());
    }

    @Bean
    CircuitBreakerAspect mydddCircuitBreakerAspect(ObjectProvider<CircuitBreakerPort> circuitBreakerPorts,
            MydddResilienceProperties properties) {
        return new CircuitBreakerAspect(circuitBreakerPorts, properties.getFallback().getMethodPrefix());
    }
}
