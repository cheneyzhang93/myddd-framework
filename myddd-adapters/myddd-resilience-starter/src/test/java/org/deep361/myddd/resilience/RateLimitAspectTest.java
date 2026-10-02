package org.deep361.myddd.resilience;

import java.io.IOException;
import java.util.List;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.resiliencefixture.ClassLevelGuardedService;
import org.deep361.myddd.resiliencefixture.GuardedService;
import org.deep361.myddd.resiliencefixture.IfaceDeclaredServiceImpl;
import org.deep361.myddd.resiliencefixture.ResilienceHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 限流切面（{@code RL1}–{@code RL16}）：一律走<b>真 CGLIB 代理</b>上的业务方法，没有一条直接调
 * {@code around(...)}——探针 U1/U5 的两条读数（绑定式切点给错值、代理形态与命中无关）只有在真代理上
 * 才读得出来，绕过代理的用例会替切面撒谎。
 *
 * <p>等待节拍全部用替身（{@link org.deep361.myddd.resiliencefixture.RecordingPollSleeper}）：一次都不真睡，
 * 所以间隔清单与尝试次数是逐字可断的。"生产节拍真的装在装配里"是 {@code AC8} 的判据，不在本类。
 */
class RateLimitAspectTest {

    private ResilienceHarness harness;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        this.harness = new ResilienceHarness();
    }

    @AfterEach
    void tearDown() {
        if (this.context != null) {
            this.context.close();
        }
        // 中断标志是用例之间的共享状态：RL11 会恢复它，别的用例必须从干净的值开始。
        Thread.interrupted();
    }

    /** 一个用例一套真代理上下文：建两次会把 {@code harness} 里的那份端口记录切成两半。 */
    private AnnotationConfigApplicationContext context() {
        if (this.context == null) {
            this.context = this.harness.rateLimitContext();
        }
        return this.context;
    }

    private GuardedService guarded() {
        return context().getBean(GuardedService.class);
    }

    private List<String> calls() {
        return this.harness.port.calls;
    }

    /**
     * {@code RL1}：{@code permits} 留空归一为 1，缺省名与"归一"从同一次端口调用里读出来。
     *
     * <p>返回值 {@code "done"} 只可能来自 {@code joinPoint.proceed()}，所以"放行之后业务真的执行了"
     * 由它钉住，不需要再去代理对象上挖目标实例。
     */
    @Test
    void should_ask_the_port_for_one_permit_when_the_annotation_leaves_permits_at_zero() {
        assertThat(guarded().defaultKey()).isEqualTo("done");
        assertThat(calls()).containsExactly("GuardedService.defaultKey/1");
    }

    /** {@code RL2}：注解写几个许可，端口就收到几个，不折算。 */
    @Test
    void should_ask_the_port_for_the_declared_permits() {
        guarded().fivePermits();
        assertThat(calls()).containsExactly("GuardedService.fivePermits/5");
    }

    /**
     * {@code RL3}：负数形态在<b>取端口之前</b>拒绝。
     *
     * <p>{@code calls} 必须为空——否则"拒绝"发生在端口之后，报的就是端口的那句而不是注解的这句。
     */
    @Test
    void should_reject_a_negative_permits_or_timeout_before_touching_the_port() {
        GuardedService bean = guarded();
        assertThatThrownBy(bean::negativePermits)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("@RateLimit(permits=-1)")
                .hasMessageContaining("GuardedService#negativePermits");
        assertThatThrownBy(bean::negativeTimeout)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("@RateLimit(timeout=-1)")
                .hasMessageContaining("GuardedService#negativeTimeout");
        assertThat(calls()).isEmpty();
    }

    /** {@code RL4}：{@code timeout=0} 的可观察定义——端口一次，节拍零次。 */
    @Test
    void should_call_the_port_exactly_once_when_the_timeout_is_zero() {
        this.harness.port.neverGrant();
        assertThrows(BizException.class, this.guarded()::defaultKey);
        assertThat(calls()).hasSize(1);
        assertThat(this.harness.sleeper.intervals).isEmpty();
    }

    /** {@code RL5}：{@code timeout=8} ⇒ 间隔取下限 1ms、计划 9 次（{@code 1 + 8/1}）。 */
    @Test
    void should_retry_the_computed_attempt_count_when_the_port_keeps_refusing() {
        this.harness.port.neverGrant();
        assertThrows(BizException.class, this.guarded()::eightMillisBudget);
        assertThat(calls()).hasSize(9);
        assertThat(this.harness.sleeper.intervals).containsExactly(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L);
        assertThat(this.harness.port.resources()).containsOnly("GuardedService.eightMillisBudget");
    }

    /** {@code RL6}：{@code 1000/8=125} 被上限夹成 50ms，于是 21 次。 */
    @Test
    void should_clamp_the_poll_interval_to_fifty_millis() {
        this.harness.port.neverGrant();
        assertThrows(BizException.class, this.guarded()::oneSecondBudget);
        assertThat(this.harness.sleeper.intervals).containsOnly(50L);
        assertThat(calls()).hasSize(21);
    }

    /** {@code RL7}：{@code 1/8=0} 被下限托成 1ms，于是 2 次；这条与 {@code IQ4} 是同一件事的两面。 */
    @Test
    void should_clamp_the_poll_interval_to_one_millisecond() {
        this.harness.port.neverGrant();
        assertThrows(BizException.class, this.guarded()::oneMillisBudget);
        assertThat(this.harness.sleeper.intervals).containsExactly(1L);
        assertThat(calls()).hasSize(2);
    }

    /**
     * {@code RL8}：护栏截断发生时的读法——间隔取<b>注解侧</b>的 25（不是护栏侧的 12），
     * 六个机读数里"实际预算"与"护栏"同为 100。
     *
     * <p>两个数各取一侧才分得清截断发生没发生：都从注解读的话，护栏换成 5000 也照样全绿。
     */
    @Test
    void should_cap_the_wait_budget_at_the_configured_ceiling() {
        this.harness.ceilingMillis = 100L;
        this.harness.port.neverGrant();
        BizException rejection = assertThrows(BizException.class, this.guarded()::twoHundredMillisBudget);
        assertThat(calls()).hasSize(5);
        assertThat(rejection.getMessageArgs()).matches(args ->
                args.length == 6 && Long.valueOf(200L).equals(args[0]) && Long.valueOf(100L).equals(args[1])
                        && Long.valueOf(100L).equals(args[2]) && Long.valueOf(25L).equals(args[3])
                        && Integer.valueOf(5).equals(args[4]) && Integer.valueOf(5).equals(args[5]));
    }

    /** {@code RL9}：六个机读数进 {@code getMessageArgs()}，一个都不进展示文案。 */
    @Test
    void should_report_six_machine_readable_numbers_when_the_budget_is_exhausted() {
        this.harness.port.neverGrant();
        BizException rejection = assertThrows(BizException.class, this.guarded()::defaultKey);
        assertThat(rejection.getErrorCode()).isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS);
        assertThat(rejection.getMessageArgs()).containsExactly(0L, 0L, 5_000L, 0L, 1, 1);
        assertThat(rejection.getMessage()).isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS.defaultMessage());
    }

    /** {@code RL10}（R12）：自定义文案只换展示文案，错误身份与六个数一律不变。 */
    @Test
    void should_keep_the_error_identity_when_the_annotation_carries_its_own_text() {
        this.harness.port.neverGrant();
        BizException rejection = assertThrows(BizException.class, this.guarded()::refusedWithMessage);
        assertThat(rejection).isInstanceOf(BizException.class);
        assertThat(rejection.getMessage()).isEqualTo("系统繁忙，请稍后再试");
        assertThat(rejection.getErrorCode()).isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS);
        assertThat(rejection.getMessageArgs()).containsExactly(8L, 8L, 5_000L, 1L, 9, 9);
    }

    /**
     * {@code RL11}：节拍被中断 ⇒ 恢复中断标志、停止再问端口，且六个数的末两位是"计划 9 / 实际 2"。
     *
     * <p>与 {@code RL5} 的区别全在这两个数上：预算耗尽时两者相等，中断时前者更大。
     */
    @Test
    void should_restore_the_interrupt_flag_and_stop_asking_when_the_sleep_is_interrupted() {
        Thread.interrupted();
        this.harness.port.neverGrant();
        this.harness.sleeper.interruptOnSleep = 2;
        BizException rejection = assertThrows(BizException.class, this.guarded()::eightMillisBudget);
        assertThat(rejection.getErrorCode()).isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS);
        assertThat(rejection.getMessageArgs()).matches(args ->
                Integer.valueOf(9).equals(args[4]) && Integer.valueOf(2).equals(args[5]));
        assertThat(calls()).hasSize(2);
        assertThat(Thread.interrupted()).isTrue();
    }

    /** {@code RL12}：业务返回值原样出去，业务的受检异常也原样出去——限流路径一律不重新装箱。 */
    @Test
    void should_pass_the_business_value_and_its_checked_exception_through_untouched() {
        GuardedService bean = guarded();
        assertThat(bean.byArgument("A-1")).isEqualTo("A-1");
        assertThat(calls()).containsExactly("GuardedService.byArgument/1");
        IOException thrown = assertThrows(IOException.class, bean::throwsChecked);
        assertThat(thrown).hasMessage("业务自己的错");
        assertThat(thrown).isNotInstanceOf(RuntimeException.class);
    }

    /**
     * {@code RL13}：代理类名不渗进资源名。
     *
     * <p>先断"确实走了 CGLIB"，否则这条可能只是"根本没建代理"的假绿（探针 F5 的实测锚点）。
     */
    @Test
    void should_not_leak_the_proxy_class_name_into_the_resource_name() {
        GuardedService bean = guarded();
        assertThat(bean.getClass().getName()).contains("$$SpringCGLIB$$");
        bean.defaultKey();
        assertThat(this.harness.port.resources()).containsExactly("GuardedService.defaultKey");
    }

    /** {@code RL14}：容器里没有端口不是启动期事故，报话发生在第一次调用，且点名两个 provider。 */
    @Test
    void should_diagnose_both_provider_starters_when_no_port_bean_exists() {
        this.context = this.harness.rateLimitContextWithoutPort();
        GuardedService bean = this.context.getBean(GuardedService.class);
        assertThatThrownBy(bean::defaultKey)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd-limiter-sentinel-starter")
                .hasMessageContaining("myddd-limiter-resilience4j-starter")
                .hasMessageContaining("GuardedService.defaultKey");
        assertThat(calls()).isEmpty();
    }

    /**
     * {@code RL15}：注解只写在接口上时一次都不拦（探针 U3/U4 的"零命中"在真代理上复现）。
     *
     * <p>第三次调用是这一条的对照组：实现侧补一份之后门就开了，且用的是实现侧的 {@code key}——
     * 缺它则"接口与实现都不拦"也能让前两次全绿。
     */
    @Test
    void should_never_fire_when_the_annotation_is_declared_only_on_the_interface() {
        IfaceDeclaredServiceImpl bean = context().getBean(IfaceDeclaredServiceImpl.class);
        assertThat(bean.declaredInIface("A")).isEqualTo("A");
        assertThat(bean.anotherInIface("B")).isEqualTo("B");
        assertThat(calls()).isEmpty();
        assertThat(bean.bothSides("C")).isEqualTo("C");
        assertThat(calls()).containsExactly("impl-side/1");
    }

    /**
     * {@code RL16}：类型级注解对每个方法独立生效（缺省名按方法算），方法级那一整份压过类型级。
     *
     * <p>②的物证是调用次数：类级 {@code timeout=8} 若跟着生效，第 19 项之后还会再有 8 项。
     */
    @Test
    void should_apply_the_class_level_declaration_to_every_method_and_let_the_method_level_win_wholesale() {
        this.harness.port.neverGrant();
        ClassLevelGuardedService bean = context().getBean(ClassLevelGuardedService.class);
        assertThrows(BizException.class, bean::first);
        assertThrows(BizException.class, () -> bean.second("x"));
        assertThat(this.harness.port.resources()).containsOnly(
                "ClassLevelGuardedService.first", "ClassLevelGuardedService.second");
        assertThat(calls()).hasSize(18);

        this.harness.port.grantFromCall = 1;
        assertThat(bean.methodWins()).isEqualTo("done");
        assertThat(calls()).hasSize(19);
        assertThat(calls().get(18)).isEqualTo("ClassLevelGuardedService.methodWins/3");
    }
}
