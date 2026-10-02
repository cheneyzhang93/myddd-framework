package org.deep361.myddd.limiter.sentinel.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.deep361.myddd.limiter.sentinel.SentinelResourceNames;
import org.deep361.myddd.limiter.sentinel.circuit.SentinelCircuitBreakerPort;
import org.deep361.myddd.limiter.sentinel.rate.SentinelRateLimitPort;
import org.deep361.myddd.limiter.sentinel.rule.SentinelRulePublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 唯一一份自动装配 {@code AC1}–{@code AC6}，{@code ApplicationContextRunner} 起手，与 Task 41/42/44 同法。
 */
class MydddLimiterSentinelAutoConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MydddLimiterSentinelAutoConfiguration.class));
    }

    private void clear() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }

    /** 沿 cause 链找我们自己的那一格异常：{@code rootCause()} 会穿过包装读到厂商异常。 */
    private static FrameworkException frameworkCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FrameworkException specific) {
                return specific;
            }
        }
        throw new AssertionError("启动失败链里没有 FrameworkException：" + failure);
    }

    /**
     * 类路径夹具落到本次用例的私有目录：{@code rule-location} 只认文件系统路径，而坏文件形态
     * 是这份字节的一部分，不该在用例里重打一遍（与 r4j 侧的 {@code /resilience4j-rules/} 同法）。
     */
    private static Path fixture(Path dir, String name) throws IOException {
        try (InputStream in = MydddLimiterSentinelAutoConfigurationTest.class
                .getResourceAsStream("/sentinel-rules/" + name)) {
            return Files.write(dir.resolve(name),
                    Objects.requireNonNull(in, "夹具缺失：" + name).readAllBytes());
        }
    }

    /** {@code AC1}：零配置 ⇒ 三个 bean 在场、名字逐字、注册类型是端口接口。 */
    @Test
    void should_assemble_two_ports_and_the_publisher_with_the_default_configuration() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RateLimitPort.class);
            assertThat(context).hasSingleBean(CircuitBreakerPort.class);
            assertThat(context).hasSingleBean(SentinelRulePublisher.class);
            assertThat(context).hasBean("mydddRateLimitPort");
            assertThat(context).hasBean("mydddCircuitBreakerPort");
            assertThat(context).hasBean("mydddSentinelRulePublisher");
            assertThat(context.getBean("mydddRateLimitPort")).isInstanceOf(SentinelRateLimitPort.class);
            assertThat(context.getBean("mydddCircuitBreakerPort")).isInstanceOf(SentinelCircuitBreakerPort.class);
            assertThat(context.getBean(RateLimitPort.class)).isSameAs(context.getBean("mydddRateLimitPort"));
        });
    }

    /** {@code AC2}：业务自带一个端口 ⇒ 只让位那一个，另一端口与 publisher 照旧在场。 */
    @Test
    void should_back_off_from_a_port_the_application_defined() {
        RateLimitPort custom = (resource, permits) -> true;
        runner().withBean(RateLimitPort.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(RateLimitPort.class)).hasSize(1);
            assertThat(context.getBean(RateLimitPort.class)).isSameAs(custom);
            assertThat(context).hasSingleBean(CircuitBreakerPort.class);
            assertThat(context).hasSingleBean(SentinelRulePublisher.class);
        });
    }

    /** {@code AC3}：memory 来源启动期一次下发都不做，不清别人已经下的规则表。 */
    @Test
    void should_not_publish_anything_when_the_rule_source_is_memory() {
        clear();
        FlowRule keep = new FlowRule();
        keep.setResource(SentinelResourceNames.rate("ac3-keep"));
        keep.setGrade(RuleConstant.FLOW_GRADE_QPS);
        keep.setCount(5D);
        FlowRuleManager.loadRules(List.of(keep));
        runner().withPropertyValues("myddd.limiter.sentinel.rule-source=memory").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SentinelRulePublisher.class);
            assertThat(FlowRuleManager.getRules()).hasSize(1);
        });
    }

    /** {@code AC4}：file 来源启动即装载并下发带前缀名。 */
    @Test
    void should_load_the_file_and_publish_prefixed_names_at_startup(@TempDir Path dir) throws IOException {
        clear();
        Path file = fixture(dir, "valid.json");
        runner().withPropertyValues(
                "myddd.limiter.sentinel.rule-source=file",
                "myddd.limiter.sentinel.rule-location=" + file).run(context -> {
            assertThat(context).hasNotFailed();
            List<FlowRule> flow = FlowRuleManager.getRules();
            List<DegradeRule> degrade = DegradeRuleManager.getRules();
            assertThat(flow).hasSize(2);
            assertThat(degrade).hasSize(2);
            assertThat(flow.stream().map(FlowRule::getResource).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder("myddd:rate:order:create", "myddd:rate:order:query");
            assertThat(degrade.stream().map(DegradeRule::getResource).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder("myddd:circuit:payment:capture", "myddd:circuit:inventory:lock");
            assertThat(flow.stream().filter(r -> r.getResource().equals("myddd:rate:order:create"))
                    .findFirst().orElseThrow().getCount()).isEqualTo(50.0D);
        });
    }

    /**
     * {@code AC5}：坏文件（截断 / 未知字段 / count=-1）都在启动期响，链上必须有一条 {@code FrameworkException}。
     *
     * <p><b>为什么不用 {@code .rootCause()} 断类型</b>：读取器把 Jackson 的异常包成
     * {@code FrameworkException} 当 cause 挂着，{@code rootCause()} 会一路穿到最深处那一格，
     * 于是读到的是 {@code UnexpectedEndOfInputException}（实测 {@code verifyG7} 的第一条红），
     * 那正是"包装有没有生效"的反证。⇒ 沿链找 {@code FrameworkException}，而不是找最深处。
     *
     * <p><b>消息里能不能看到文件路径分两种</b>：形态错（截断、未知字段）由读取器报，消息以
     * "规则文件 {路径}" 开头；值域错（{@code count=-1}）由校验器报，消息只有段名 + 下标 + 资源名
     * ——{@code SentinelRulePublisher} 根本不知道那份字节来自哪个路径。所以每条坏文件配自己的断言片段。
     */
    @Test
    void should_fail_the_startup_and_name_the_file_when_the_document_is_unusable(@TempDir Path dir) throws IOException {
        for (String[] bad : new String[][] {
                {"malformed.json", "不是可解析的 JSON"},
                {"unknown-field.json", "不认识的字段"},
                {"negative-count.json", "count=-1.0"}}) {
            clear();
            Path file = fixture(dir, bad[0]);
            runner().withPropertyValues(
                    "myddd.limiter.sentinel.rule-source=file",
                    "myddd.limiter.sentinel.rule-location=" + file).run(context -> {
                assertThat(context).hasFailed();
                FrameworkException failure = frameworkCause(context.getStartupFailure());
                assertThat(failure).hasMessageContaining(bad[1]);
                if (!"negative-count.json".equals(bad[0])) {
                    assertThat(failure).hasMessageContaining(file.toString());
                }
            });
        }
    }

    /**
     * {@code AC5b}：{@code count=0} 是合法的零配额开关 ⇒ 启动成功、规则真进表、第一次调用被拒。
     *
     * <p>计划里那句"0 会被厂商静默丢弃"实测不成立（{@code ProbeRS6} B/E 格），本用例把纠正后的
     * 三件事一起钉住：装配不响、{@code getRules()} 有 1 条、端口 {@code tryAcquire} 返回 false。
     */
    @Test
    void should_start_up_and_block_everything_for_a_zero_quota_rule(@TempDir Path dir) throws IOException {
        clear();
        Path file = fixture(dir, "zero-count.json");
        runner().withPropertyValues(
                "myddd.limiter.sentinel.rule-source=file",
                "myddd.limiter.sentinel.rule-location=" + file).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(FlowRuleManager.getRules())
                    .extracting(FlowRule::getResource).containsExactly("myddd:rate:order:create");
            assertThat(context.getBean(RateLimitPort.class).tryAcquire("order:create", 1)).isFalse();
        });
    }

    /** {@code AC6}：关掉 provider ⇒ 三个 bean 都不在；选中另一家 ⇒ 同样不在且启动不失败。 */
    @Test
    void should_not_assemble_any_bean_when_the_provider_is_disabled() {
        runner().withPropertyValues("myddd.limiter.sentinel.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RateLimitPort.class);
            assertThat(context).doesNotHaveBean(CircuitBreakerPort.class);
            assertThat(context).doesNotHaveBean(SentinelRulePublisher.class);
        });
        runner().withPropertyValues("myddd.limiter.provider=resilience4j").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RateLimitPort.class);
            assertThat(context).doesNotHaveBean(CircuitBreakerPort.class);
            assertThat(context).doesNotHaveBean(SentinelRulePublisher.class);
        });
    }
}
