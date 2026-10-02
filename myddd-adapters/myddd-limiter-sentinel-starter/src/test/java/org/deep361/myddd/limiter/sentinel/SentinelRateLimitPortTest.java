package org.deep361.myddd.limiter.sentinel;

import java.util.List;

import org.deep361.myddd.limiter.sentinel.rate.SentinelRateLimitPort;
import org.junit.jupiter.api.Test;

import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流端口 {@code AB1}–{@code AB6}。夹具：用例内直接 {@code FlowRuleManager.loadRules(...)} 下发，
 * 不经过 publisher（下发面归 RS 组）；每条第一句先清空两张表，用例之间不靠执行次序。
 */
class SentinelRateLimitPortTest {

    private final SentinelRateLimitPort port = new SentinelRateLimitPort();

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

    /** {@code AB1}：批量逐笔扣减（count=3：要 2 成功、再要 2 失败、要 1 成功）。 */
    @Test
    void should_charge_every_permit_of_the_batch() {
        FlowRuleManager.loadRules(List.of());
        loadFlow("ab1-x", 3);
        assertThat(port.tryAcquire("ab1-x", 2)).isTrue();
        assertThat(port.tryAcquire("ab1-x", 2)).isFalse();
        assertThat(port.tryAcquire("ab1-x", 1)).isTrue();
    }

    /** {@code AB2}：整批吃掉后额度不退还（先要 3 吃满，再要 1 被拒）。 */
    @Test
    void should_not_refund_the_budget_when_the_ticket_is_released() {
        FlowRuleManager.loadRules(List.of());
        loadFlow("ab2-x", 3);
        assertThat(port.tryAcquire("ab2-x", 3)).isTrue();
        assertThat(port.tryAcquire("ab2-x", 1)).isFalse();
    }

    /** {@code AB3}：被限流立即返回 false、不阻塞调用线程。 */
    @Test
    void should_deny_without_blocking_the_caller_thread() {
        FlowRuleManager.loadRules(List.of());
        loadFlow("ab3-x", 1);
        assertThat(port.tryAcquire("ab3-x", 1)).isTrue();
        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            assertThat(port.tryAcquire("ab3-x", 1)).isFalse();
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertThat(elapsedMillis).as("端口里不许偷偷睡").isLessThan(200L);
    }

    /** {@code AB4}：一票即走不在业务线程留 Sentinel 上下文（200 次全放行且 context 为 null）。 */
    @Test
    void should_leave_no_sentinel_context_on_the_calling_thread() {
        FlowRuleManager.loadRules(List.of());
        loadFlow("ab4-x", 200);
        int granted = 0;
        for (int i = 0; i < 200; i++) {
            if (port.tryAcquire("ab4-x", 1)) {
                granted++;
            }
        }
        assertThat(granted).isEqualTo(200);
        assertThat(ContextUtil.getContext()).isNull();
    }

    /** {@code AB5}：无规则资源全放行（"规则缺失却不失败"的运行期代价）。 */
    @Test
    void should_pass_through_a_resource_that_has_no_rule_at_all() {
        FlowRuleManager.loadRules(List.of());
        for (int i = 0; i < 5; i++) {
            assertThat(port.tryAcquire("ab5-x", 1)).isTrue();
        }
    }

    /** {@code AB6}：非法入参在厂商调用之前拒，且不留上下文、不改规则表。 */
    @Test
    void should_reject_illegal_arguments_without_touching_the_vendor() {
        FlowRuleManager.loadRules(List.of());
        loadFlow("ab6-x", 5);
        int sizeBefore = FlowRuleManager.getRules().size();
        assertThatThrownBy(() -> port.tryAcquire("ab6-x", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ContextUtil.getContext()).isNull();
        assertThat(FlowRuleManager.getRules()).hasSize(sizeBefore);
        assertThatThrownBy(() -> port.tryAcquire("ab6-x", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ContextUtil.getContext()).isNull();
        assertThat(FlowRuleManager.getRules()).hasSize(sizeBefore);
        assertThatThrownBy(() -> port.tryAcquire("   ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ContextUtil.getContext()).isNull();
        assertThat(FlowRuleManager.getRules()).hasSize(sizeBefore);
        assertThatThrownBy(() -> port.tryAcquire(null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ContextUtil.getContext()).isNull();
        assertThat(FlowRuleManager.getRules()).hasSize(sizeBefore);
    }
}
