package org.deep361.myddd.limiter.sentinel.rule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.deep361.myddd.kernel.common.error.FrameworkException;

/**
 * 规则文档的语义校验：启动期（{@code rule-source=file} 的那份字节）与每次热更新都过这里一次。
 *
 * <p><b>为什么字段面那么窄还要再校验一遍值域</b>：Step 4 的读取器只认字段名，所以
 * {@code {"flow":[{"resource":"a","count":-1}]}} 是一份形态完全合法的文档。厂商会把这条
 * <b>静默丢弃</b>（一手实测 {@code ProbeRS6}：{@code FlowRuleUtil.isValidRule} 给 {@code false}、
 * {@code FlowRuleManager.getRules()} 从 1 条变 0 条，两个过程都不报错也不写日志）。
 * 于是"限流配了 -1"的实际效果是<b>全部放行</b>——配错的人以为自己把接口关死了，运行期看到的
 * 是流量畅通。这条落差必须由人承担，所以这里拒收。
 *
 * <p><b>{@code count=0} 合法，而且必须留着</b>：同一组实测里 {@code count=0} 给
 * {@code isValidRule=true}、{@code getRules()} 仍是 1 条、第一次 {@code entry=false}——它是厂商的
 * "零配额"开关，QPS 与线程数两个 grade 行为一致（{@code ProbeRS6} 的 B 与 E 格）。写 0 的人得到的
 * 正是"全拒绝"，与直觉相符 ⇒ 框架没有理由替人拒掉一个语义正确的显式开关。这里只拒"会被静默丢掉、
 * 结果和写它的意图完全相反"的那些形态。
 *
 * <p><b>为什么消息里要带探针读数</b>：本类拒绝的每一种形态都是"厂商语法上允许"的形态，
 * 光说"不支持"会被当成框架能力缺失来提 issue。每条消息都给一句实测结论（哪个字段、实测什么），
 * 让读规则文件的人知道这不是没做完，而是做完以后确认它不成立。
 *
 * <p>所有校验一次跑完再报（{@link #validate(MydddSentinelRuleDocument)} 收集第一条失败即抛出，
 * 但每条消息都定位到段名 + 下标 + 资源名 + 字段名）：规则文件是运维手写的，逐条重跑的成本
 * 高于一次只告诉你一处。
 */
public final class SentinelRuleValidator {

    /** {@code degrade[].threshold} 取值一：窗口内异常调用占比。 */
    public static final String THRESHOLD_EXCEPTION_RATIO = "exception-ratio";

    /** {@code degrade[].threshold} 取值二：窗口内耗时超阈值的调用占比。 */
    public static final String THRESHOLD_RT = "rt";

    /** 明确拒收的第三种厂商形态（异常数），消息里要把它点名，否则运维以为写错了拼法。 */
    private static final String THRESHOLD_EXCEPTION_COUNT = "exception-count";

    /**
     * 校验整份文档。
     *
     * @throws FrameworkException 第一条不成立的规则；消息含段名、下标、资源名、字段实际值与期望形态
     */
    public void validate(MydddSentinelRuleDocument document) {
        rejectDuplicatedResources("flow", document.flow().stream().map(MydddSentinelRuleDocument.FlowRuleSpec::resource).toList());
        rejectDuplicatedResources("degrade",
                document.degrade().stream().map(MydddSentinelRuleDocument.DegradeRuleSpec::resource).toList());
        for (int i = 0; i < document.flow().size(); i++) {
            validateFlow(i, document.flow().get(i));
        }
        for (int i = 0; i < document.degrade().size(); i++) {
            validateDegrade(i, document.degrade().get(i));
        }
    }

    private void validateFlow(int index, MydddSentinelRuleDocument.FlowRuleSpec spec) {
        String path = "flow[" + index + "]";
        requireResource(spec.resource(), path);
        Double count = spec.count();
        if (count == null) {
            throw fail(path, spec.resource(), "count", "缺失",
                    "一个非负数——这个资源在一个秒内允许通过多少个请求（0 表示一条都不许过）");
        }
        if (!Double.isFinite(count) || count < 0.0D) {
            throw fail(path, spec.resource(), "count", String.valueOf(count),
                    "非负的有限数（0 合法：厂商的零配额开关，真的全拒绝）。实测：负数与 NaN 会让厂商把这条规则"
                            + "整条丢掉（FlowRuleUtil.isValidRule=false、getRules() 少一条，不报错也不日志），"
                            + "于是这个资源全放行");
        }
    }

    private void validateDegrade(int index, MydddSentinelRuleDocument.DegradeRuleSpec spec) {
        String path = "degrade[" + index + "]";
        requireResource(spec.resource(), path);
        String threshold = spec.threshold();
        if (threshold == null || threshold.trim().isEmpty()) {
            throw fail(path, spec.resource(), "threshold", "缺失",
                    THRESHOLD_EXCEPTION_RATIO + " 或 " + THRESHOLD_RT);
        }
        if (THRESHOLD_EXCEPTION_COUNT.equals(threshold)) {
            throw fail(path, spec.resource(), "threshold", threshold,
                    THRESHOLD_EXCEPTION_RATIO + " 或 " + THRESHOLD_RT + "。实测：异常数形态即使 minCalls=3、"
                            + "连续 3 次失败后第 4 次调用仍然放行（异常数落到的时间点和统计粒度不由这两个值决定），"
                            + "一个开闸时刻不可预测的熔断器比没有更坏");
        }
        if (!THRESHOLD_EXCEPTION_RATIO.equals(threshold) && !THRESHOLD_RT.equals(threshold)) {
            throw fail(path, spec.resource(), "threshold", threshold,
                    THRESHOLD_EXCEPTION_RATIO + " 或 " + THRESHOLD_RT + "（逐字小写）");
        }
        requireRatio(path, spec);
        if (THRESHOLD_RT.equals(threshold)) {
            Double maxRtMs = spec.maxRtMs();
            if (maxRtMs == null) {
                throw fail(path, spec.resource(), "maxRtMs", "缺失",
                        "一个正的毫秒阈值——rt 形态的语义是“耗时超过 maxRtMs 的调用占比达到 ratio 就开闸”，"
                                + "两个值缺任何一个就是一条永不熔断的哑规则");
            }
            if (!Double.isFinite(maxRtMs) || maxRtMs <= 0.0D) {
                throw fail(path, spec.resource(), "maxRtMs", String.valueOf(maxRtMs), "正的有限数");
            }
        } else if (spec.maxRtMs() != null) {
            throw fail(path, spec.resource(), "maxRtMs", String.valueOf(spec.maxRtMs()),
                    "在 threshold=" + THRESHOLD_EXCEPTION_RATIO + " 时不能给——给了会被厂商当成未使用字段忽略，"
                            + "而它看起来像一个生效的阈值");
        }
        requirePositiveInt(path, spec.resource(), "minCalls", spec.minCalls(),
                "至少 1——没有最小调用数时窗口里头几次采样的比例没有下限，开闸点跟着调用速率漂");
        requirePositiveInt(path, spec.resource(), "openSeconds", spec.openSeconds(),
                "至少 1（单位是秒）。实测：开闸后 600ms 仍然拒绝、1400ms 放行（timeWindow=1 时），"
                        + "写成 0 等于没有开闸期，半开探测会紧跟着失败发生");
        Integer windowMs = spec.windowMs();
        if (windowMs == null || windowMs < 1000) {
            throw fail(path, spec.resource(), "windowMs", String.valueOf(windowMs),
                    "至少 1000ms。统计窗口比一秒还短时，一个窗口的样本数完全由调用速率决定，"
                            + "而限流与熔断的等待节拍（切面下限 1ms）会让亚秒窗口里的样本数不可复现——"
                            + "同一条注解在不同机器上开闸与否，这类用例只能写成区间断言");
        }
    }

    private void requireRatio(String path, MydddSentinelRuleDocument.DegradeRuleSpec spec) {
        Double ratio = spec.ratio();
        if (ratio == null) {
            throw fail(path, spec.resource(), "ratio", "缺失", "0 到 1 之间的比例（不是百分数）");
        }
        if (!Double.isFinite(ratio) || ratio <= 0.0D || ratio > 1.0D) {
            throw fail(path, spec.resource(), "ratio", String.valueOf(ratio),
                    "0 到 1 之间（含 1）的比例。写成 50 这类百分数是这类文件最常见的手误，"
                            + "而厂商按占比解释它：50 会被当成“5000%”，规则永远不触发");
        }
    }

    private void requirePositiveInt(String path, String resource, String field, Integer value, String expectation) {
        if (value == null) {
            throw fail(path, resource, field, "缺失", expectation);
        }
        if (value <= 0) {
            throw fail(path, resource, field, String.valueOf(value), expectation);
        }
    }

    private void requireResource(String resource, String path) {
        if (resource == null || resource.trim().isEmpty()) {
            throw fail(path, resource, "resource", String.valueOf(resource),
                    "非空白的资源名，且必须与注解 @RateLimit/@CircuitBreaker 的 key 逐字一致"
                            + "（内部前缀由框架拼，文件里不要写 myddd:rate: 这类前缀）");
        }
    }

    /**
     * 同一段里的重名拒收：厂商按资源名建索引，同名两条规则的实际生效次序没有可读的判据，
     * 而"哪条生效"会直接决定限流阈值是多少。跨段同名是<b>合法</b>且被刻意支持的（两个端口
     * 各有各的前缀，见 Step 5b 的 {@code RS12}），所以这里逐段判而不是全表判。
     */
    private void rejectDuplicatedResources(String section, List<String> resources) {
        Set<String> seen = new HashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (String resource : resources) {
            if (resource != null && !seen.add(resource)) {
                duplicated.add(resource);
            }
        }
        if (!duplicated.isEmpty()) {
            throw new FrameworkException(section + " 段有重复的 resource：" + duplicated
                    + "——同一段里一个资源名只许一条规则：厂商按名索引，两条同名规则的生效次序没有判据，"
                    + "于是“哪一条生效”取决于下发次序");
        }
    }

    private FrameworkException fail(String path, String resource, String field, String actual, String expectation) {
        return new FrameworkException("规则 " + path + "（resource=" + resource + "）的 " + field + "=" + actual
                + " 不成立，期望：" + expectation);
    }
}
