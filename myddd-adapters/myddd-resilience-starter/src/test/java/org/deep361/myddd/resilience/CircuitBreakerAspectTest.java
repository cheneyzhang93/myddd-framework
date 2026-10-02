package org.deep361.myddd.resilience;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.resiliencefixture.ResilienceFixtures;
import org.deep361.myddd.resiliencefixture.ResilienceHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 熔断切面（{@code CB1}–{@code CB15}）：一律走真 CGLIB 代理，且全部跑在
 * {@link ResilienceHarness#circuitBreakerContext()} 上——那个上下文里限流切面也在场，
 * {@code CB11} 才有"降级这一次有没有第二次进切面链"的可观察计数。
 *
 * <p>判据核心是端口替身的那个 {@code actionStarted}：业务自己抛 {@code SERVICE_DEGRADED} 与端口把它
 * 挡在门外在异常类型上完全一样，只有"动作有没有跑起来"能分开（{@code CB2}/{@code CB4} 是同一枚硬币的两面）。
 */
class CircuitBreakerAspectTest {

    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void closeContexts() {
        this.contexts.forEach(AnnotationConfigApplicationContext::close);
        this.contexts.clear();
    }

    /**
     * 一个用例一套上下文，且建一次就存起来：fallback 前缀在切面<b>构造</b>时定死，改前缀必须重建上下文；
     * 而同一个上下文里多次调用（{@code CB15}）必须复用，否则端口计数被切在两个容器里读不出顺序。
     */
    private ResilienceFixtures.BreakerService breaker(ResilienceHarness harness) {
        AnnotationConfigApplicationContext context = harness.circuitBreakerContext();
        this.contexts.add(context);
        return context.getBean(ResilienceFixtures.BreakerService.class);
    }

    private List<String> resources(ResilienceHarness harness) {
        return harness.breakerPort.resources;
    }

    /** {@code CB1}：端口放行时，业务返回值原样出去，资源名按缺省规则算。 */
    @Test
    void should_return_what_the_business_returned_when_the_port_grants() {
        ResilienceHarness harness = new ResilienceHarness();
        assertThat(breaker(harness).plain()).isEqualTo("ran");
        assertThat(resources(harness)).containsExactly("BreakerService.plain");
        assertThat(harness.breakerPort.actionStarted).isTrue();
        assertThat(harness.breakerPort.failureTypes).isEmpty();
    }

    /**
     * {@code CB2}：fallback 只在"端口在动作开始之前就拒"时调用。
     *
     * <p>{@code resources} 只有一项是这一条的下半段：fallback 自己不该再进端口。
     */
    @Test
    void should_call_the_fallback_only_when_the_port_refused_before_the_action_ran() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        assertThat(breaker(harness).withFallback()).isEqualTo("degraded");
        assertThat(harness.breakerPort.actionStarted).isFalse();
        assertThat(resources(harness)).containsExactly("BreakerService.withFallback");
    }

    /** {@code CB3}：注解留空 = 不降级（内核 javadoc 原文），拒绝原样抛给调用方。 */
    @Test
    void should_propagate_the_refusal_unchanged_when_the_annotation_declares_no_fallback() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        ResilienceFixtures.BreakerService bean = breaker(harness);
        BizException refusal = assertThrows(BizException.class, bean::plain);
        assertThat(refusal.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_DEGRADED);
        assertThat(refusal).isNotInstanceOf(FrameworkException.class);
    }

    /**
     * {@code CB4}：业务自己在动作里抛 {@code SERVICE_DEGRADED} 时绝不降级。
     *
     * <p>"返回了 {@code wouldBeWrongFallback()} 的结果"意味着把一个 bug 静默换成了一个看起来正常的返回值，
     * 所以这一格的物证就是抛出的正是那一个实例、且 {@code actionStarted} 为 {@code true}。
     */
    @Test
    void should_not_degrade_when_the_business_itself_throws_the_degraded_error() {
        ResilienceHarness harness = new ResilienceHarness();
        ResilienceFixtures.BreakerService bean = breaker(harness);
        BizException thrown = assertThrows(BizException.class, bean::businessThrowsDegradedItself);
        assertThat(thrown.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_DEGRADED);
        assertThat(harness.breakerPort.actionStarted).isTrue();
        assertThat(thrown).hasMessage(CommonErrorCode.SERVICE_DEGRADED.defaultMessage());
    }

    /** {@code CB5}：签名兼容的第二形态是"原参数表 + 一个 {@code Throwable} 尾参"，实参把拒绝递进去。 */
    @Test
    void should_hand_the_cause_to_a_fallback_that_declares_a_trailing_throwable() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        assertThat(breaker(harness).withCauseTakingFallback("A")).isEqualTo("cause:BizException");
    }

    /**
     * {@code CB6}：前缀生效时命中 {@code cb + 首字母大写的原名}（夹具里是 {@code cbPrefixedTarget}）；
     * 前缀为空串时只剩注解原文一个候选，找不到就在消息里写出候选名、被保护方法与签名要求。
     */
    @Test
    void should_honour_the_fallback_prefix_and_name_both_candidates_when_nothing_matches() {
        ResilienceHarness prefixed = new ResilienceHarness();
        prefixed.fallbackPrefix = "cb";
        prefixed.breakerPort.grantCalls = 0;
        assertThat(breaker(prefixed).usesPrefix()).isEqualTo("prefixed");

        ResilienceHarness blank = new ResilienceHarness();
        blank.breakerPort.grantCalls = 0;
        ResilienceFixtures.BreakerService bean = breaker(blank);
        assertThatThrownBy(bean::usesPrefix)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("[prefixedTarget]")
                .hasMessageContaining("BreakerService#usesPrefix")
                .hasMessageContaining("参数是 [] 或其后加一个 Throwable");
    }

    /**
     * {@code CB7}：两个候选名各解出一个方法 ⇒ 快速失败。
     *
     * <p>"先试前缀、试不到退回原名"是一种静默偏好，团队里两个人对同一个注解读出不同方法而用例全绿；
     * 这一条钉的就是"本框架不做这种偏好"。
     */
    @Test
    void should_fail_fast_when_both_the_prefixed_and_raw_names_resolve_a_method() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.fallbackPrefix = "cb";
        harness.breakerPort.grantCalls = 0;
        ResilienceFixtures.BreakerService bean = breaker(harness);
        assertThatThrownBy(bean::ambiguousPrefix)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("BreakerService#cbBoth")
                .hasMessageContaining("BreakerService#both")
                .hasMessageContaining("myddd.resilience.fallback.method-prefix");
    }

    /** {@code CB8}：返回类型不兼容在装配面就拒，否则等到下游真挂时抛 {@code ClassCastException}。 */
    @Test
    void should_reject_a_fallback_whose_return_type_is_not_assignable() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        ResilienceFixtures.BreakerService bean = breaker(harness);
        assertThatThrownBy(bean::wrongReturnTypeFallback)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.Integer")
                .hasMessageContaining("java.lang.String");
    }

    /**
     * {@code CB9}：跨包私有 fallback 必须 {@code makeAccessible}。
     *
     * <p>探针 F9 的 {@code NO-EXCEPTION} 是夹具假象（嵌套类对宿主类可见）；本夹具与切面<b>不同包</b>，
     * 省掉那一句就变成 {@code IllegalAccessException}。
     */
    @Test
    void should_reach_a_private_fallback_declared_in_another_package_than_the_aspect() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        assertThat(breaker(harness).privateFallback()).isEqualTo("private-ok");
    }

    /** {@code CB10}：fallback 允许声明在父类，查找必须走继承链（探针 F6 实测 {@code getDeclaredMethod} 找不到）。 */
    @Test
    void should_find_a_fallback_declared_on_a_super_class() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        assertThat(breaker(harness).usesInheritedFallback()).isEqualTo("from-base");
    }

    /**
     * {@code CB11}：fallback 在<b>裸目标</b>上调用，所以它自带的注解不会被第二次拦。
     *
     * <p>限流端口计数为空是这一条唯一的读法：接收者写成代理时 {@code @RateLimit} 会再触发一次，
     * 更糟的是同一条 {@code @CircuitBreaker} 把自己拖进"拒绝→找 fallback→再拒绝"的递归。
     */
    @Test
    void should_invoke_the_fallback_on_the_raw_target_so_no_annotation_runs_twice() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        assertThat(breaker(harness).fallbackIsAnnotated()).isEqualTo("annotated");
        assertThat(harness.port.calls).isEmpty();
        assertThat(resources(harness)).hasSize(1);
    }

    /**
     * {@code CB12}：装箱只活在这段接缝里——调用方 {@code catch (IOException)} 抓得到，
     * 而端口"看到的"是装箱类型。
     *
     * <p>后半段是交给 Task 45/46 的输入：两家 provider 的失败判定都按"任何异常"算，不按类型白名单，
     * 所以装箱不丢失败率；哪天有人加白名单，这一格就是那条决策的现场证据。
     */
    @Test
    void should_unwrap_the_boxed_checked_exception_after_the_port_returns() {
        ResilienceHarness harness = new ResilienceHarness();
        ResilienceFixtures.BreakerService bean = breaker(harness);
        IOException caught = assertThrows(IOException.class, bean::throwsChecked);
        assertThat(caught).hasMessage("熔断里的业务受检异常");
        assertThat(caught).isNotInstanceOf(RuntimeException.class);
        assertThat(harness.breakerPort.failureTypes).containsExactly(ActionThrowableFailure.class);
    }

    /** {@code CB13}：按参数类型精确匹配，绝不按名字取第一条（探针 F7 实测两个同名签名都在）。 */
    @Test
    void should_match_the_fallback_by_parameter_types_not_by_name_alone() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        assertThat(breaker(harness).picksBySignature(7)).isEqualTo("int:7");
    }

    /** {@code CB14}：fallback 自己炸了 ⇒ 解包后原样抛，不套 {@code InvocationTargetException}、不换成 BizException。 */
    @Test
    void should_propagate_a_fallback_failure_unchanged_and_unwrapped() {
        ResilienceHarness harness = new ResilienceHarness();
        harness.breakerPort.grantCalls = 0;
        ResilienceFixtures.BreakerService bean = breaker(harness);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, bean::fallbackExplodes);
        assertThat(thrown).hasMessage("fallback 自己炸了");
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown).isNotInstanceOf(BizException.class);
    }

    /**
     * {@code CB15}：三个阈值只做形态校验，且校验在取端口<b>之前</b>。
     *
     * <p>⑤是与 {@code plain()} 的逐字对照：阈值写了合法值也不改变任何一次端口调用。这一格就是
     * "阈值不生效"被<b>断言</b>的地方，而不是一段没人读的 javadoc（不对称表一行由 Task 47 承载）。
     */
    @Test
    void should_validate_the_three_thresholds_as_shape_only_and_change_nothing_when_they_are_positive() {
        ResilienceHarness harness = new ResilienceHarness();
        ResilienceFixtures.BreakerService bean = breaker(harness);
        assertThatThrownBy(bean::negativeFailureRate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("failureRateThreshold=-1")
                .hasMessageContaining("0（由外部配置决定）或 1–100")
                .hasMessageContaining("BreakerService#negativeFailureRate");
        assertThatThrownBy(bean::failureRateAboveHundred)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("failureRateThreshold=101");
        assertThatThrownBy(bean::negativeWindow)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("slidingWindowSize=-1")
                .hasMessageContaining("≥1");
        assertThatThrownBy(bean::negativeWaitDuration)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("waitDurationInOpenState=-1")
                .hasMessageContaining("≥1");
        assertThat(resources(harness)).isEmpty();

        assertThat(bean.thresholdsDeclaredButUnused()).isEqualTo("ran");
        assertThat(bean.plain()).isEqualTo("ran");
        assertThat(resources(harness)).containsExactly("BreakerService.thresholdsDeclaredButUnused",
                "BreakerService.plain");
    }
}
