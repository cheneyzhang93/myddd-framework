package org.deep361.myddd.limiter.sentinel.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sentinel 规则文档的模型：两段（{@code flow} / {@code degrade}），字段只保留"在两个端口的形态下
 * 真的有可用取值"的那些。
 *
 * <p><b>为什么没有 {@code grade} / {@code strategy} / {@code limitApp} / {@code controlBehavior}
 * / {@code clusterMode}</b>：探针读数（P45-B、P45-C、P45-D、P45-J）逐条给了结论——线程数形态
 * （{@code grade=0}）在"立即 exit"的记账下永不拒绝；关联与链路形态（{@code strategy=1/2}）
 * 需要被关联资源自己的上下文，端口调用里没有；{@code limitApp} 非 {@code default} 在
 * 无来源（origin）的进程里静默失效；{@code controlBehavior} 的两种非快速失败形态要么阻塞
 * （实测 1009ms，违反 R8 的"端口非阻塞、等待只在切面"）要么按预热期放大放行；集群形态需要
 * 独立的 token server。留下这些字段就等于提供一批"能写、写了不生效"的配置，而本框架对这种
 * 形态的处置一直是拒收（与 Task 44 `IQ3` 同一条）。字段的可用取值与实测读数全表见 Step 5 的
 * {@code SentinelRuleValidator} javadoc。
 *
 * <p>所有可选值都用包装类型：{@code null} 是"这一条没写这个字段"，{@code 0} 是"写了 0"。
 * 两者必须能分辨，否则 Step 5 的必填校验无从落地（缺省值一律由校验器拒绝，不在此处填充）。
 */
public final class MydddSentinelRuleDocument {

    /** 空文档：两段都没有规则。{@code memory} 来源的就是这一份。 */
    public static final MydddSentinelRuleDocument EMPTY =
            new MydddSentinelRuleDocument(List.of(), List.of());

    private final List<FlowRuleSpec> flow;
    private final List<DegradeRuleSpec> degrade;

    public MydddSentinelRuleDocument(List<FlowRuleSpec> flow, List<DegradeRuleSpec> degrade) {
        this.flow = List.copyOf(Objects.requireNonNull(flow, "flow 段不可为 null，空段请用空列表"));
        this.degrade = List.copyOf(Objects.requireNonNull(degrade, "degrade 段不可为 null，空段请用空列表"));
    }

    /** 限流段（对应 {@code RateLimitPort}）。 */
    public List<FlowRuleSpec> flow() {
        return this.flow;
    }

    /** 熔断段（对应 {@code CircuitBreakerPort}）。 */
    public List<DegradeRuleSpec> degrade() {
        return this.degrade;
    }

    /** 一条限流规则：裸资源名 + 一个秒内允许的通过数。 */
    public static final class FlowRuleSpec {

        private final String resource;
        private final Double count;

        public FlowRuleSpec(String resource, Double count) {
            this.resource = resource;
            this.count = count;
        }

        public String resource() {
            return this.resource;
        }

        public Double count() {
            return this.count;
        }

        @Override
        public String toString() {
            return "flow{resource=" + this.resource + ", count=" + this.count + "}";
        }
    }

    /**
     * 一条熔断规则。两种形态（{@code threshold}）各用不同的字段组合，必填关系由校验器判：
     * {@code exception-ratio} 用 {@code ratio}；{@code rt} 用 {@code maxRtMs} 与 {@code ratio}
     * （Sentinel 的 RT 形态语义是"窗口内耗时超过 {@code maxRtMs} 的调用占比达到 {@code ratio} 就开闸"，
     * 两个值缺一不可——只给一个就是永远不熔断的哑规则）。
     */
    public static final class DegradeRuleSpec {

        private final String resource;
        private final String threshold;
        private final Double ratio;
        private final Double maxRtMs;
        private final Integer minCalls;
        private final Integer windowMs;
        private final Integer openSeconds;

        public DegradeRuleSpec(String resource, String threshold, Double ratio, Double maxRtMs,
                Integer minCalls, Integer windowMs, Integer openSeconds) {
            this.resource = resource;
            this.threshold = threshold;
            this.ratio = ratio;
            this.maxRtMs = maxRtMs;
            this.minCalls = minCalls;
            this.windowMs = windowMs;
            this.openSeconds = openSeconds;
        }

        public String resource() {
            return this.resource;
        }

        public String threshold() {
            return this.threshold;
        }

        public Double ratio() {
            return this.ratio;
        }

        public Double maxRtMs() {
            return this.maxRtMs;
        }

        public Integer minCalls() {
            return this.minCalls;
        }

        public Integer windowMs() {
            return this.windowMs;
        }

        public Integer openSeconds() {
            return this.openSeconds;
        }

        @Override
        public String toString() {
            return "degrade{resource=" + this.resource + ", threshold=" + this.threshold
                    + ", ratio=" + this.ratio + ", maxRtMs=" + this.maxRtMs + ", minCalls=" + this.minCalls
                    + ", windowMs=" + this.windowMs + ", openSeconds=" + this.openSeconds + "}";
        }
    }

    /** 供校验器与用例拼诊断用：两段的全部裸资源名（含重复，顺序为文档顺序）。 */
    public List<String> resources() {
        List<String> names = new ArrayList<>();
        this.flow.forEach(spec -> names.add(spec.resource()));
        this.degrade.forEach(spec -> names.add(spec.resource()));
        return names;
    }
}
