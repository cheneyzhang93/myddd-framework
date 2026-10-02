package org.deep361.myddd.limiter.resilience4j.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code myddd.limiter.resilience4j.rule-location} 指向的那份 JSON 的内存形状（框架侧文档模型，不是 r4j 的
 * {@code CircuitBreakerConfig}）。
 *
 * <p><b>字段面与 Sentinel 侧同构</b>（{@code MydddSentinelRuleDocument}）：{@code flow[]} 只有
 * {@code resource}+{@code count}，{@code degrade[]} 只有 {@code resource}/{@code threshold}/
 * {@code ratio}/{@code maxRtMs}/{@code minCalls}/{@code windowMs}/{@code openSeconds}。业务写的是
 * <b>能力语义</b>，两家的 {@code threshold} 取值集合分别是 {@code error-ratio|slow-ratio}（r4j）与
 * {@code error-ratio|error-count|rt}（Sentinel）⇒ 交集由 Task 47 的等价性用例给，差集由各家的校验器拒收。
 *
 * <p><b>数值字段全部是包装类型</b>：{@code Integer}/{@code Float} 的 {@code null} 表示"这一格没写"，
 * 与"写了 0"必须可分辨——r4j 的 {@code limitForPeriod=0} 与 {@code failureRateThreshold=0} 都在
 * {@code build()} 当场抛 {@code IllegalArgumentException}（实测 {@code Probe46b Q5/Q7} 的消息：
 * {@code LimitForPeriod should be greater than 0}、{@code failureRateThreshold must be greater than 0
 * and lower than 100}），把"没写"读成 0 会让一条手抖删掉字段的规则变成启动失败而不是"用缺省"。
 */
public class MydddResilience4jRuleDocument {

    /** 限流段：{@code count} = 每个 1 秒刷新周期放行的许可数（QPS 语义，与 Sentinel 侧同义）。 */
    public static class FlowEntry {
        private String resource;
        private Integer count;

        public String getResource() { return this.resource; }
        public void setResource(String resource) { this.resource = resource; }
        public Integer getCount() { return this.count; }
        public void setCount(Integer count) { this.count = count; }
    }

    /** 熔断段：一条 degrade 记录换算成一份 {@code CircuitBreakerConfig}。 */
    public static class DegradeEntry {
        private String resource;
        /** {@code error-ratio} 或 {@code slow-ratio}。其他取值（Sentinel 侧的 {@code error-count}、{@code rt}）由校验器拒收。 */
        private String threshold;
        /** 百分比，开区间 (0,100)——{@code 100} 在 r4j 侧写不出来，见校验器 javadoc 与 D-161。 */
        private Float ratio;
        /** 仅 {@code slow-ratio} 用：慢调用阈值（毫秒）。 */
        private Long maxRtMs;
        /** 参与统计的最小调用数（= r4j 的 {@code minimumNumberOfCalls}）。 */
        private Integer minCalls;
        /** 滑动窗口长度（毫秒），必须是 1000 的整数倍（TIME_BASED 窗口单位是秒，实测 {@code Probe46 L}）。 */
        private Long windowMs;
        /** 开闸时长（秒），= r4j 的 {@code waitDurationInOpenState}。 */
        private Long openSeconds;

        public String getResource() { return this.resource; }
        public void setResource(String resource) { this.resource = resource; }
        public String getThreshold() { return this.threshold; }
        public void setThreshold(String threshold) { this.threshold = threshold; }
        public Float getRatio() { return this.ratio; }
        public void setRatio(Float ratio) { this.ratio = ratio; }
        public Long getMaxRtMs() { return this.maxRtMs; }
        public void setMaxRtMs(Long maxRtMs) { this.maxRtMs = maxRtMs; }
        public Integer getMinCalls() { return this.minCalls; }
        public void setMinCalls(Integer minCalls) { this.minCalls = minCalls; }
        public Long getWindowMs() { return this.windowMs; }
        public void setWindowMs(Long windowMs) { this.windowMs = windowMs; }
        public Long getOpenSeconds() { return this.openSeconds; }
        public void setOpenSeconds(Long openSeconds) { this.openSeconds = openSeconds; }
    }

    private final List<FlowEntry> flow = new ArrayList<>();
    private final List<DegradeEntry> degrade = new ArrayList<>();

    public List<FlowEntry> getFlow() { return this.flow; }
    public List<DegradeEntry> getDegrade() { return this.degrade; }
}
