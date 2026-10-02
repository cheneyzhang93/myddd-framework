package org.deep361.myddd.limiter.sentinel;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.limiter.sentinel.circuit.SentinelCircuitBreakerPort;
import org.deep361.myddd.limiter.sentinel.rate.SentinelRateLimitPort;
import org.junit.jupiter.api.Test;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 熔断端口 {@code AB7}–{@code AB13}。夹具与 {@code AB1}–{@code AB6} 同法：用例内直接下发规则，
 * 每条第一句先清空两张表。开闸类用例统一 {@code exception-ratio} + minCalls=2 + windowMs=3000 +
 * openSeconds=10（10 秒开闸期比整组执行时间长，"开闸状态跨用例存活"不会被误当隔离生效）。
 */
class SentinelCircuitBreakerPortTest {

    private final SentinelCircuitBreakerPort port = new SentinelCircuitBreakerPort();
    private final SentinelRateLimitPort ratePort = new SentinelRateLimitPort();

    private void clear() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }

    private void loadDegrade(String resource, int minCalls, double ratio, int windowMs, int openSeconds) {
        DegradeRule rule = new DegradeRule();
        rule.setResource(SentinelResourceNames.circuit(resource));
        rule.setLimitApp(RuleConstant.LIMIT_APP_DEFAULT);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(ratio);
        rule.setMinRequestAmount(minCalls);
        rule.setStatIntervalMs(windowMs);
        rule.setTimeWindow(openSeconds);
        DegradeRuleManager.loadRules(List.of(rule));
    }

    private void loadFlow(String resource, double count) {
        FlowRule rule = new FlowRule();
        rule.setResource(SentinelResourceNames.rate(resource));
        rule.setLimitApp(RuleConstant.LIMIT_APP_DEFAULT);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setStrategy(RuleConstant.STRATEGY_DIRECT);
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        rule.setCount(count);
        FlowRuleManager.loadRules(List.of(rule));
    }

    /** {@code AB7}：失败在 close 之前记账——两次失败后第三次开闸且动作根本不执行。 */
    @Test
    void should_record_the_failure_before_releasing_the_entry() {
        clear();
        loadDegrade("ab7-x", 2, 0.5D, 3000, 10);
        IllegalStateException failure = new IllegalStateException("下游超时");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> port.execute("ab7-x", () -> {
                throw failure;
            })).isSameAs(failure);
        }
        AtomicInteger executed = new AtomicInteger();
        assertThatThrownBy(() -> port.execute("ab7-x", () -> executed.incrementAndGet()))
                .isInstanceOf(BizException.class)
                .isInstanceOfSatisfying(BizException.class,
                        biz -> assertThat(biz.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_DEGRADED));
        assertThat(executed.get()).isZero();
    }

    /** {@code AB8}：关闭态原样上抛业务异常；开闸态翻译成 BizException(cause=DegradeException) 且文案无前缀。 */
    @Test
    void should_propagate_business_failures_and_translate_only_the_vendor_refusal() {
        clear();
        loadDegrade("ab8-x", 2, 0.5D, 3000, 10);
        IllegalStateException business = new IllegalStateException("库存不足");
        // ① 关闭态：单次失败原样上抛（minCalls=2，第一发还不足以开闸）
        assertThatThrownBy(() -> port.execute("ab8-x", () -> {
            throw business;
        })).isSameAs(business).isNotInstanceOf(BizException.class);
        // ② 第二发失败把闸打开，第三发才是被拒的调用
        assertThatThrownBy(() -> port.execute("ab8-x", () -> {
            throw business;
        })).isSameAs(business);
        assertThatThrownBy(() -> port.execute("ab8-x", () -> "never"))
                .isInstanceOf(BizException.class)
                .isInstanceOfSatisfying(BizException.class, biz -> {
                    assertThat(biz.getCause()).isInstanceOf(DegradeException.class);
                    assertThat(biz.getMessage()).doesNotContain("myddd:");
                });
    }

    /** {@code AB9}：Error 也记账（两次 AssertionError 后第三次开闸）。 */
    @Test
    void should_count_an_error_as_a_failure_too() {
        clear();
        loadDegrade("ab9-x", 2, 0.5D, 3000, 10);
        AssertionError failure = new AssertionError("断言失败");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> port.execute("ab9-x", () -> {
                throw failure;
            })).isSameAs(failure);
        }
        assertThatThrownBy(() -> port.execute("ab9-x", () -> "never"))
                .isInstanceOf(BizException.class);
    }

    /** {@code AB10}：flow 规则改不掉熔断端口——同裸名下限流生效、熔断照常执行返回。 */
    @Test
    void should_not_let_a_flow_rule_reach_the_circuit_port() {
        clear();
        loadFlow("ab10-biz", 1);
        assertThat(ratePort.tryAcquire("ab10-biz", 1)).isTrue();
        assertThat(ratePort.tryAcquire("ab10-biz", 1)).isFalse();
        AtomicInteger counter = new AtomicInteger();
        assertThat(port.<Integer>execute("ab10-biz", counter::incrementAndGet)).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(1);
    }

    /**
     * {@code AB11}：开闸的熔断改不掉限流端口——同裸名下熔断开闸，限流仍放行。
     *
     * <p>两个用例各用自己的裸名：{@code loadRules(List.of())} 清的是<b>规则表</b>，不是
     * 资源统计节点——同名资源在同一个秒窗口里的 pass 计数会跨用例存活（publisher javadoc 里
     * 那条"清空重发也清不掉 QPS 计数"的实测）。共用 "same-biz" 时本类的用例次序一变，
     * 第一发 {@code tryAcquire} 就撞上上一用例吃掉的唯一额度（实测 {@code verifyG7}：
     * "Expecting value to be true but was false"）。
     */
    @Test
    void should_not_let_an_open_breaker_reach_the_rate_port() {
        clear();
        loadDegrade("ab11-biz", 2, 0.5D, 3000, 10);
        IllegalStateException failure = new IllegalStateException("下游超时");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> port.execute("ab11-biz", () -> {
                throw failure;
            })).isSameAs(failure);
        }
        assertThatThrownBy(() -> port.execute("ab11-biz", () -> "never")).isInstanceOf(BizException.class);
        assertThat(ratePort.tryAcquire("ab11-biz", 1)).isTrue();
    }

    /** {@code AB12}：恢复窗口后放一次探测，成功后清样本；全模块唯一真睡处（1300ms）。 */
    @Test
    void should_open_for_one_probe_after_the_recovery_window_and_then_clear_the_samples() throws InterruptedException {
        clear();
        loadDegrade("ab12-x", 2, 0.5D, 3000, 1);
        IllegalStateException failure = new IllegalStateException("下游超时");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> port.execute("ab12-x", () -> {
                throw failure;
            })).isSameAs(failure);
        }
        assertThatThrownBy(() -> port.execute("ab12-x", () -> "blocked")).isInstanceOf(BizException.class);
        Thread.sleep(1300L);
        assertThat(port.execute("ab12-x", () -> "probe")).isEqualTo("probe");
        assertThat(port.execute("ab12-x", () -> "ran1")).isEqualTo("ran1");
        assertThat(port.execute("ab12-x", () -> "ran2")).isEqualTo("ran2");
    }

    /** {@code AB13}：文案永不外露前缀；连续调用不改进程级规则表。 */
    @Test
    void should_never_expose_the_internal_prefix_and_never_mutate_the_rule_table() {
        clear();
        loadDegrade("ab13-x", 2, 0.5D, 3000, 10);
        assertThatThrownBy(() -> port.execute("   ", () -> "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("资源名")
                .hasMessageNotContaining("myddd:");
        assertThatThrownBy(() -> ratePort.tryAcquire("   ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("资源名")
                .hasMessageNotContaining("myddd:");

        List<DegradeRule> before = DegradeRuleManager.getRules();
        for (int i = 0; i < 50; i++) {
            port.execute("ab13-x", () -> "ok");
        }
        assertThat(DegradeRuleManager.getRules()).isEqualTo(before);
    }
}
