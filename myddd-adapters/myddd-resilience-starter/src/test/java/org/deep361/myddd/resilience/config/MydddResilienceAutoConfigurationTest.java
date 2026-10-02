package org.deep361.myddd.resilience.config;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.deep361.myddd.resilience.CircuitBreakerAspect;
import org.deep361.myddd.resilience.PollSleeper;
import org.deep361.myddd.resilience.RateLimitAspect;
import org.deep361.myddd.resiliencefixture.GuardedService;
import org.deep361.myddd.resiliencefixture.RecordingCircuitBreakerPort;
import org.deep361.myddd.resiliencefixture.RecordingRateLimitPort;
import org.deep361.myddd.resiliencefixture.ResilienceFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真自动配置产出的那两个切面 bean（{@code AC1}–{@code AC8}）。
 *
 * <p>{@link MydddResilienceAutoConfiguration} 不含 {@code @EnableAspectJAutoProxy}（开不开代理是应用的决定），
 * 所以 {@code ApplicationContextRunner} 的上下文默认<b>不建代理</b>：本组一律不断"注解生效"（那是
 * {@code RL}/{@code CB}/{@code OR} 的判据面），只断 bean 在场/不在场与两个键真的接到了构造参数上。
 * 需要真调用的四条（{@code AC3}/{@code AC4}/{@code AC6}/{@code AC8}）额外挂一份只代表"应用自己开了代理"
 * 的测试配置。
 */
class MydddResilienceAutoConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MydddResilienceAutoConfiguration.class));
    }

    /** 代表"应用自己写 {@code @EnableAspectJAutoProxy}"的那一步，与 Boot 缺省形态一致（CGLIB）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopTestConfig {

        @Bean
        GuardedService guardedService() {
            return new GuardedService();
        }

        @Bean
        ResilienceFixtures.BreakerService breakerService() {
            return new ResilienceFixtures.BreakerService();
        }
    }

    private RecordingRateLimitPort rateLimitPort() {
        return new RecordingRateLimitPort();
    }

    /** {@code AC1}：不写任何键，两个切面都在场。 */
    @Test
    void should_contribute_both_aspects_and_the_properties_by_default() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RateLimitAspect.class);
            assertThat(context).hasSingleBean(CircuitBreakerAspect.class);
            assertThat(context).hasSingleBean(MydddResilienceProperties.class);
            // PollSleeper 不是 bean（装配 javadoc 第三条）：要证它的行为就走 AC8，不要在这里证类型。
            assertThat(context).doesNotHaveBean(PollSleeper.class);
        });
    }

    /**
     * {@code AC2}：总开关关掉 ⇒ 两个切面都不产出。
     *
     * <p>此后注解一次都不会被拦，既不报错也不日志——这是本框架唯一被支持的静默形态，代价写在
     * {@code package-info} 里，所以这一条必须有钉子。
     */
    @Test
    void should_contribute_nothing_when_the_switch_is_off() {
        runner().withPropertyValues("myddd.resilience.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RateLimitAspect.class);
            assertThat(context).doesNotHaveBean(CircuitBreakerAspect.class);
        });
    }

    /**
     * {@code AC3}：护栏键真的递到了构造参数上。
     *
     * <p>同一个注解在没有该键时会被问 21 次（{@code RL6}），这里只被问 1 次——这一差值就是接线的机读证据。
     */
    @Test
    void should_pass_the_busy_wait_ceiling_from_the_property_into_the_aspect() {
        RecordingRateLimitPort port = rateLimitPort();
        port.neverGrant();
        runner().withUserConfiguration(AopTestConfig.class)
                .withBean(RateLimitPort.class, () -> port)
                .withPropertyValues("myddd.resilience.rate-limit.timeout-max-busy-wait=2ms")
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(GuardedService.class).oneSecondBudget())
                            .isInstanceOf(BizException.class);
                    assertThat(port.calls).hasSize(1);
                });
    }

    /** {@code AC4}：前缀键真的递到了构造参数上（不接则 {@code CB6②} 的读法在这里复现：抛 FrameworkException）。 */
    @Test
    void should_pass_the_fallback_prefix_from_the_property_into_the_aspect() {
        RecordingCircuitBreakerPort port = new RecordingCircuitBreakerPort();
        port.grantCalls = 0;
        runner().withUserConfiguration(AopTestConfig.class)
                .withBean(CircuitBreakerPort.class, () -> port)
                .withPropertyValues("myddd.resilience.fallback.method-prefix=cb")
                .run(context -> assertThat(context.getBean(ResilienceFixtures.BreakerService.class).usesPrefix())
                        .isEqualTo("prefixed"));
    }

    /**
     * {@code AC5}：{@code @PostConstruct} 的校验没被"只在测试里手工 new properties"绕过去。
     *
     * <p>属性绑定给出的非法值让上下文<b>启动失败</b>，消息三段各自在场（键名 / 实际值 / 期望形态）。
     */
    @Test
    void should_fail_startup_when_the_ceiling_is_bound_to_an_illegal_value() {
        runner().withPropertyValues("myddd.resilience.rate-limit.timeout-max-busy-wait=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(FrameworkException.class);
            assertThat(String.valueOf(org.springframework.core.NestedExceptionUtils
                    .getMostSpecificCause(context.getStartupFailure()).getMessage()))
                    .contains("myddd.resilience.rate-limit.timeout-max-busy-wait", "PT0S", "请给一个正的时长");
        });
    }

    /**
     * {@code AC6}：一个端口 bean 都不注册 ⇒ 启动成功、切面在场，报错发生在第一次调用。
     *
     * <p>与 {@code RL14} 的分工：那条证手工装配的切面，这条证<b>真自动配置</b>产出的切面走的是同一条
     * {@code ObjectProvider} 路径。
     */
    @Test
    void should_start_without_any_port_and_diagnose_on_first_call() {
        runner().withUserConfiguration(AopTestConfig.class).run(context -> {
            assertThat(context).hasSingleBean(RateLimitAspect.class);
            assertThatThrownBy(() -> context.getBean(GuardedService.class).defaultKey())
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd-limiter-sentinel-starter")
                    .hasMessageContaining("myddd-limiter-resilience4j-starter");
        });
    }

    /**
     * {@code AC7}：两个 {@code RateLimitPort} bean 同时在场 ⇒ 直接把 Spring 的歧义异常抛出去，不吞、不翻译。
     *
     * <p>"两个 provider 同时在场"的快速失败归 Task 45/46 的 provider 选择器；这里钉的是"壳不会把一个
     * 歧义伪装成一个诊断"。
     */
    @Test
    void should_surface_the_container_ambiguity_instead_of_inventing_a_diagnosis() {
        runner().withUserConfiguration(AopTestConfig.class)
                .withBean("portA", RecordingRateLimitPort.class, this::rateLimitPort)
                .withBean("portB", RecordingRateLimitPort.class, this::rateLimitPort)
                .run(context -> assertThatThrownBy(() -> context.getBean(GuardedService.class).defaultKey())
                        .isInstanceOf(NoUniqueBeanDefinitionException.class)
                        .hasMessageContaining("portA")
                        .hasMessageContaining("portB"));
    }

    /**
     * {@code AC8}：装配里装的是<b>生产</b>节拍（{@code ThreadPollSleeper}），不是替身。
     *
     * <p>只断耗时下界：这台机器只会让它更慢，不会更快，所以它不是一条会偶发红的时序用例。
     * {@code RL} 组全部用替身，证不了这件事，所以这一格必须留在这里。
     */
    @Test
    void should_use_the_real_poll_sleeper_when_nothing_replaces_it() {
        RecordingRateLimitPort port = rateLimitPort();
        port.neverGrant();
        runner().withUserConfiguration(AopTestConfig.class)
                .withBean(RateLimitPort.class, () -> port)
                .run(context -> {
                    long started = System.nanoTime();
                    assertThatThrownBy(() -> context.getBean(GuardedService.class).twoHundredMillisBudget())
                            .isInstanceOf(BizException.class);
                    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
                    assertThat(port.calls).hasSize(9);
                    assertThat(elapsedMillis).as("8 次 × 25ms 的真睡，留 40% 余量").isGreaterThanOrEqualTo(150L);
                });
    }

}