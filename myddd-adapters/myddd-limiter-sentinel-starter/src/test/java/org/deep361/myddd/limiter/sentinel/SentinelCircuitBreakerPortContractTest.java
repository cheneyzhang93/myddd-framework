package org.deep361.myddd.limiter.sentinel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.limiter.sentinel.circuit.SentinelCircuitBreakerPort;
import org.deep361.myddd.limiter.sentinel.rule.MydddSentinelRuleDocument;
import org.deep361.myddd.limiter.sentinel.rule.SentinelRulePublisher;
import org.deep361.myddd.limiter.sentinel.rule.SentinelRuleValidator;
import org.deep361.myddd.testing.contract.CircuitBreakerPortContractTestBase;

/**
 * {@code CB-C1}–{@code CB-C7} 的 Sentinel 侧执行。
 *
 * <p><b>{@code tripThreshold()=3} 与三个下发值的取法</b>：基类要求"窗口 {@code N} 个调用、失败率 50%、
 * 最小调用数 = {@code N}"。Sentinel 没有"按次数划的滑动窗口"，对应物是 {@code statIntervalMs}
 * （按时间）+ {@code minRequestAmount}（按次数），所以这里翻成
 * {@code ratio=0.5} + {@code minCalls=N} + {@code windowMs=3000}：{@code N} 次连续失败在一个 3 秒窗口内
 * 一定落在一起（用例里那 {@code N} 次调用是微秒级的事），窗口宽度对结果没有影响，而 3 秒这个值
 * 同时满足校验器"窗口不少于 1000ms"的下限。开闸时长给 10 秒——它比整组用例的执行时间还长，
 * 于是"开闸状态跨用例存活"这一格不可能被误当成隔离生效；每个用例开头的 {@code configure}
 * 靠清空再发布把上一次的开闸状态真正重置（P45-J 的 {@code 3d_clear_then_republish_first_call=ran}）。
 *
 * <p><b>这一组一条都不睡</b>：熔断的状态由规则对象携带，清空重发就是一次全新的统计（与 QPS 计数
 * 不同，那个清不掉）。所以 {@code CB-C} 的墙钟成本是 0，而 {@code RL-C} 是 10.8 秒——同一个模块里
 * 两类等待的差别必须写清楚，否则下一次有人会把"契约用例都真等"当成规则去加 sleep。
 */
class SentinelCircuitBreakerPortContractTest extends CircuitBreakerPortContractTestBase {

    private final CircuitBreakerPort port = new SentinelCircuitBreakerPort();
    private final Map<String, Integer> windows = new LinkedHashMap<>();
    private final SentinelRulePublisher publisher = new SentinelRulePublisher(() -> MydddSentinelRuleDocument.EMPTY);

    @Override
    protected CircuitBreakerPort circuitBreakerPort() {
        return this.port;
    }

    @Override
    protected int tripThreshold() {
        return 3;
    }

    @Override
    protected void configure(String resource, int slidingWindowSize) {
        this.windows.put(resource, slidingWindowSize);
        this.publisher.publish(document());
    }

    private MydddSentinelRuleDocument document() {
        List<MydddSentinelRuleDocument.DegradeRuleSpec> degrade = new ArrayList<>();
        this.windows.forEach((resource, size) -> degrade.add(
                new MydddSentinelRuleDocument.DegradeRuleSpec(resource,
                        SentinelRuleValidator.THRESHOLD_EXCEPTION_RATIO, 0.5D, null, size, 3_000, 10)));
        // flow 段给空表：本类不碰限流命名空间（RS12/AB10/AB11 就是钉这条界线的）。
        return new MydddSentinelRuleDocument(List.of(), degrade);
    }
}
