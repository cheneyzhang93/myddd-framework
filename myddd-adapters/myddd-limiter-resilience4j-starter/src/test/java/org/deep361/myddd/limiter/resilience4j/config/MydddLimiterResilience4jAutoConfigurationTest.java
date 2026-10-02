package org.deep361.myddd.limiter.resilience4j.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.deep361.myddd.limiter.resilience4j.circuit.Resilience4jCircuitBreakerPort;
import org.deep361.myddd.limiter.resilience4j.rate.Resilience4jRateLimitPort;
import org.deep361.myddd.limiter.resilience4j.rule.Resilience4jRuleDocumentReader;
import org.deep361.myddd.limiter.resilience4j.rule.Resilience4jRulePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 唯一一份自动装配的装配面（{@code AC} 组）：条件放不放行、bean 齐不齐、业务自己给的注册表让不让位、
 * 端口与 publisher 是不是接在同两张注册表上。
 */
class MydddLimiterResilience4jAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddLimiterResilience4jAutoConfiguration.class));

    /** AC1：两家都不写、只引本模块 ⇒ 该在的 bean 一个不缺，端口各只有一个。 */
    @Test
    void should_assemble_the_whole_surface_when_no_provider_is_named() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RateLimiterRegistry.class);
            assertThat(context).hasSingleBean(CircuitBreakerRegistry.class);
            assertThat(context).hasSingleBean(Resilience4jRuleDocumentReader.class);
            assertThat(context).hasSingleBean(Resilience4jRulePublisher.class);
            assertThat(context).hasSingleBean(RateLimitPort.class);
            assertThat(context).hasSingleBean(CircuitBreakerPort.class);
            assertThat(context.getBean(RateLimitPort.class)).isInstanceOf(Resilience4jRateLimitPort.class);
            assertThat(context.getBean(CircuitBreakerPort.class))
                    .isInstanceOf(Resilience4jCircuitBreakerPort.class);
        });
    }

    /** AC2：显式选中 Sentinel ⇒ 本模块一个 bean 都不产（端口 bean 数量为 0，由 Task 44 的诊断收口）。 */
    @Test
    void should_stand_down_when_another_provider_is_named() {
        this.runner.withPropertyValues("myddd.limiter.provider=sentinel").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RateLimitPort.class);
            assertThat(context).doesNotHaveBean(CircuitBreakerPort.class);
            assertThat(context).doesNotHaveBean(Resilience4jRulePublisher.class);
            assertThat(context).doesNotHaveBean(RateLimiterRegistry.class);
        });
    }

    /** AC3：总开关关掉 ⇒ 零 bean，而且坏值也不报错（IQ7 的装配面版本：关闭即跳过校验）。 */
    @Test
    void should_produce_nothing_and_skip_validation_when_disabled() {
        this.runner.withPropertyValues(
                "myddd.limiter.resilience4j.enabled=false",
                "myddd.limiter.resilience4j.rule-source=redis",
                "myddd.limiter.resilience4j.rule-location=/definitely/not/here.json").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RateLimitPort.class);
            assertThat(context).doesNotHaveBean(CircuitBreakerPort.class);
            assertThat(context).doesNotHaveBean(MydddLimiterResilience4jProperties.class);
        });
    }

    /** AC4：file 来源（指向夹具 {@code valid.json}）在装配期就把整张表下发；空表必须响。 */
    @Test
    void should_publish_the_document_at_startup_and_reject_an_empty_one(@TempDir Path tempDir)
            throws IOException {
        Path rules = tempDir.resolve("valid.json");
        Files.write(rules, Objects.requireNonNull(
                getClass().getResourceAsStream("/resilience4j-rules/valid.json")).readAllBytes());
        this.runner.withPropertyValues(
                "myddd.limiter.resilience4j.rule-source=file",
                "myddd.limiter.resilience4j.rule-location=" + rules).run(context -> {
            assertThat(context).hasNotFailed();
            RateLimiterRegistry registry = context.getBean(RateLimiterRegistry.class);
            assertTrue(registry.find("order:create").isPresent());
            assertTrue(registry.find("batch:import").isPresent());
            assertThat(context.getBean(CircuitBreakerRegistry.class).find("payment:capture").isPresent())
                    .isTrue();
        });

        Path empty = tempDir.resolve("empty.json");
        Files.writeString(empty, "{\"flow\":[],\"degrade\":[]}");
        this.runner.withPropertyValues(
                "myddd.limiter.resilience4j.rule-source=file",
                "myddd.limiter.resilience4j.rule-location=" + empty).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure()
                    .hasRootCauseInstanceOf(FrameworkException.class)
                    .hasStackTraceContaining("0 条规则");
        });
    }

    /** AC5：业务自己注册的注册表让自动配置退让，而端口用的就是业务那一份（按额度值认身份）。 */
    @Test
    void should_step_aside_for_a_registry_the_application_provides() {
        this.runner.withBean(RateLimiterRegistry.class, () -> {
            RateLimiterRegistry business = RateLimiterRegistry.of(RateLimiterConfig.custom()
                    .limitForPeriod(1).limitRefreshPeriod(Duration.ofDays(3650)).timeoutDuration(Duration.ZERO)
                    .build());
            business.rateLimiter("identity", RateLimiterConfig.custom()
                    .limitForPeriod(7).limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ZERO)
                    .build());
            return business;
        }).run(context -> {
            assertThat(context).hasSingleBean(RateLimiterRegistry.class);
            RateLimiterRegistry registry = context.getBean(RateLimiterRegistry.class);
            RateLimiter limiter = registry.find("identity").orElseThrow();
            assertThat(limiter.getRateLimiterConfig().getLimitForPeriod()).isEqualTo(7);
            RateLimitPort port = context.getBean(RateLimitPort.class);
            assertSame(registry, ReflectionTestUtils.getField(port, "registry"));
            assertTrue(port.tryAcquire("identity", 7));
            assertFalse(port.tryAcquire("identity", 1));
        });
    }

    /**
     * AC6：端口拿到的注册表<b>就是</b>容器那一张（publisher 下发的也是这一张）。
     * 端口 bean 的构造参数一旦被换成 {@code RateLimiterRegistry.ofDefaults()}，规则面就彻底失效——
     * 而 {@code AB} 组全部手工 new、{@code RS} 组不经过容器，这条只能在这里钉。
     */
    @Test
    void should_wire_both_ports_to_the_container_registries_not_to_their_own() {
        this.runner.run(context -> {
            RateLimiterRegistry rateRegistry = context.getBean(RateLimiterRegistry.class);
            CircuitBreakerRegistry circuitRegistry = context.getBean(CircuitBreakerRegistry.class);
            assertSame(rateRegistry,
                    ReflectionTestUtils.getField(context.getBean(RateLimitPort.class), "registry"));
            assertSame(circuitRegistry,
                    ReflectionTestUtils.getField(context.getBean(CircuitBreakerPort.class), "registry"));
            assertSame(rateRegistry,
                    ReflectionTestUtils.getField(context.getBean(Resilience4jRulePublisher.class),
                            "rateLimiterRegistry"));
        });
    }
}
