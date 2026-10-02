package org.deep361.myddd.limiter.sentinel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.deep361.myddd.limiter.sentinel.rate.SentinelRateLimitPort;
import org.deep361.myddd.limiter.sentinel.rule.MydddSentinelRuleDocument;
import org.deep361.myddd.limiter.sentinel.rule.SentinelRulePublisher;
import org.deep361.myddd.testing.contract.RateLimitPortContractTestBase;

/**
 * {@code RL-C1}–{@code RL-C6} 的 Sentinel 侧执行（基类判据见 {@code myddd-test-starter}）。
 *
 * <p><b>窗口换算，一句话讲完</b>：基类的 {@code WINDOW} 是 10 秒，而 Sentinel 的 QPS 规则一秒翻篇，
 * 所以这里的规则是 {@code count = permits}（一个秒内 {@code permits} 个请求）。两家在"第 N 次申请
 * 通不通"这一格上给同一个答案，机制并不相同——这是等价性的定义，不是缺陷。基类常量不许改小，
 * 因为它同时是 Task 47 跨厂商等价夹具的约定值。
 *
 * <p><b>{@code configure} 必须真睡 1200ms</b>：清空并重发规则<b>不清 QPS 计数</b>（P45-J 实测
 * {@code 2_clear_then_republish_restores_budget=false}）。用例之间会复用同一个资源名
 * （基类里 {@code rl-order-create} 在 {@code RL-C1}/{@code RL-C2}/{@code RL-C5} 各用一次），
 * 不睡就是上一个窗口的余额算到下一个窗口头上，表现是"额度内申请"那一条偶发红。
 * 9 次等待 × 1200ms = 10.8 秒是本模块 surefire 墙钟里最大的一笔，写在这儿是为了收口时能对表，
 * 不是为了申请豁免。
 *
 * <p><b>额度表按用例累积</b>：{@code RL-C4} 在同一条用例里先 {@code configure("rl-a",1)} 再
 * {@code configure("rl-b",1)}，然后要求两者各自独立生效。publisher 的语义是整表替换，所以子类
 * 必须自己带着这张累积表下发——不带的话第二次 {@code configure} 会把第一条规则抹掉，
 * {@code rl-a} 立刻变成"没有规则 ⇒ 全放行"，那条用例的第②句当场变红。
 */
class SentinelRateLimitPortContractTest extends RateLimitPortContractTestBase {

    /** 一个窗口的宽度在 Sentinel 侧是 1 秒；1200ms 留的是调度余量，不是"多等一点更保险"。 */
    private static final long WINDOW_SLEEP_MS = 1_200L;

    private final RateLimitPort port = new SentinelRateLimitPort();
    private final Map<String, Double> budgets = new LinkedHashMap<>();
    private final SentinelRulePublisher publisher = new SentinelRulePublisher(() -> MydddSentinelRuleDocument.EMPTY);

    @Override
    protected RateLimitPort rateLimitPort() {
        return this.port;
    }

    @Override
    protected void configure(String resource, int permits) {
        this.budgets.put(resource, (double) permits);
        this.publisher.publish(document());
        awaitFreshWindow();
    }

    @Override
    protected void awaitFreshWindow() {
        sleepOneWindow();
    }

    private MydddSentinelRuleDocument document() {
        java.util.List<MydddSentinelRuleDocument.FlowRuleSpec> flow = new ArrayList<>();
        this.budgets.forEach((resource, count) ->
                flow.add(new MydddSentinelRuleDocument.FlowRuleSpec(resource, count)));
        // degrade 段给空表：本类一条熔断规则都不下发。publisher 会把那张表清空再重载成空——
        // 与 CB 侧子类各写各的命名空间（myddd:rate: 与 myddd:circuit:），互不越界，见 RS12。
        return new MydddSentinelRuleDocument(flow, java.util.List.of());
    }

    private static void sleepOneWindow() {
        try {
            Thread.sleep(WINDOW_SLEEP_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("窗口等待被打断：契约用例的时序前提没了，继续跑只会给出假绿", interrupted);
        }
    }
}
