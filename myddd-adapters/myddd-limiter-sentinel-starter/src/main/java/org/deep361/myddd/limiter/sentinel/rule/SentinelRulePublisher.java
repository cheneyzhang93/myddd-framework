package org.deep361.myddd.limiter.sentinel.rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.limiter.sentinel.SentinelResourceNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

/**
 * 规则下发：本模块唯一的<b>热更新接缝</b>。Task 47 的管理端点、启动期的文件来源、测试夹具
 * 全都走 {@link #publish(MydddSentinelRuleDocument)} 或 {@link #reload()}，没有第二条路径。
 *
 * <p><b>两个段的下发写法不对称，而且必须不对称</b>：
 * <ul>
 *   <li>{@code flow} 直接 {@code loadRules(新表)}——厂商的替换语义在这里够用，多插一次清空只会
 *       开出一个"这一瞬不限流"的窗口（纳秒级，但没有任何收益）。</li>
 *   <li>{@code degrade} 先 {@code loadRules(List.of())} 再 {@code loadRules(新表)}。一手实测
 *       （P45-J）：把<b>同一份</b> degrade 表重发一次是 no-op（已开闸的资源保持开闸，
 *       {@code 2_clear_then_republish_first_call} 在清空重发时读到 {@code ran}、不清空时读到
 *       {@code DegradeException}）。不清空就没法把一条改过的阈值真正生效，"改了不生效"是这类
 *       配置面最难查的故障，所以这里固定清空。</li>
 * </ul>
 *
 * <p><b>清空重发也清不掉 QPS 计数</b>（同一组实测的另一条：{@code 2_clear_then_republish_restores_budget=false}）。
 * 这条差异决定了 Step 9 的 {@code RL-C} 子类必须真等窗口翻篇，不能靠"重新下发"把额度要回来。
 *
 * <p><b>下发之后必须回读</b>：{@code loadRules} 不返回失败，厂商对"它认为非法的规则"的处理是
 * <b>静默丢弃</b>（{@code ProbeRS6}：{@code count=-1} 或 {@code NaN} 的 FlowRule 让
 * {@code FlowRuleUtil.isValidRule} 给 {@code false}，而 {@code getRules()} 从 1 条变 0 条，
 * 全程无异常无日志；{@code count=0} 则合法并且真的全拒绝）。Step 5a 的校验器已经
 * 把这些形态挡在前面，回读核验是第二张网：它同时兜住两类事件——我们的换算与厂商字段面不一致，
 * 以及<b>第三方组件在我们之外重写了这张全局表</b>（Sentinel 的规则表是进程级单例，任何引了
 * {@code sentinel-core} 的代码都能改）。
 */
public final class SentinelRulePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SentinelRulePublisher.class);

    private final Supplier<MydddSentinelRuleDocument> documentSource;
    private final SentinelRuleValidator validator = new SentinelRuleValidator();

    /**
     * @param documentSource 文档来源：{@code memory} 给常量空文档，{@code file} 给"每次调用重读那份
     *                       字节"的读取器。用 {@code Supplier} 而不是直接给一份文档，是为了让
     *                       {@link #reload()} 真的能读到改过的文件（热更新的全部实现就是这一句）。
     */
    public SentinelRulePublisher(Supplier<MydddSentinelRuleDocument> documentSource) {
        this.documentSource = documentSource;
    }

    /**
     * 校验、换算、下发、回读核验。
     *
     * @return 下发的规则总条数（两段之和）
     * @throws FrameworkException 校验不过（Step 5a 的消息形态）或回读不一致
     */
    public int publish(MydddSentinelRuleDocument document) {
        this.validator.validate(document);
        List<FlowRule> flow = toFlowRules(document);
        List<DegradeRule> degrade = toDegradeRules(document);
        FlowRuleManager.loadRules(flow);
        DegradeRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(degrade);
        verifyLoaded(flow, degrade);
        LOG.info("Sentinel 规则下发完成：flow {} 条、degrade {} 条（回读核验通过；资源名带内部前缀，见 package-info）",
                flow.size(), degrade.size());
        return flow.size() + degrade.size();
    }

    /** 重读来源并整表替换。返回同样是新表条数。 */
    public int reload() {
        return publish(this.documentSource.get());
    }

    private static List<FlowRule> toFlowRules(MydddSentinelRuleDocument document) {
        List<FlowRule> rules = new ArrayList<>();
        for (MydddSentinelRuleDocument.FlowRuleSpec spec : document.flow()) {
            FlowRule rule = new FlowRule();
            // setResource 与 setLimitApp 声明在 AbstractRule 上、返回 AbstractRule，接不进 FlowRule 的链式
            // 调用（一手实测：链到这两个方法之后赋值给 FlowRule 编译不过）。所以这里逐句写，不追求形状整齐。
            rule.setResource(SentinelResourceNames.rate(spec.resource()));
            rule.setLimitApp(RuleConstant.LIMIT_APP_DEFAULT);
            rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            rule.setStrategy(RuleConstant.STRATEGY_DIRECT);
            rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
            rule.setCount(spec.count());
            rules.add(rule);
        }
        return List.copyOf(rules);
    }

    private static List<DegradeRule> toDegradeRules(MydddSentinelRuleDocument document) {
        List<DegradeRule> rules = new ArrayList<>();
        for (MydddSentinelRuleDocument.DegradeRuleSpec spec : document.degrade()) {
            DegradeRule rule = new DegradeRule();
            rule.setResource(SentinelResourceNames.circuit(spec.resource()));
            rule.setLimitApp(RuleConstant.LIMIT_APP_DEFAULT);
            rule.setStatIntervalMs(spec.windowMs());
            rule.setMinRequestAmount(spec.minCalls());
            rule.setTimeWindow(spec.openSeconds());
            if (SentinelRuleValidator.THRESHOLD_RT.equals(spec.threshold())) {
                rule.setGrade(RuleConstant.DEGRADE_GRADE_RT);
                // RT 形态在厂商对象上是"count=慢调用阈值(ms)、slowRatioThreshold=占比"，
                // 与 exception-ratio 形态的"count=占比"共用一个字段名——这就是文档必须用
                // threshold 显式分档、且校验器要求两个值齐全的原因。
                rule.setCount(spec.maxRtMs());
                rule.setSlowRatioThreshold(spec.ratio());
            } else {
                rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
                rule.setCount(spec.ratio());
            }
            rules.add(rule);
        }
        return List.copyOf(rules);
    }

    private void verifyLoaded(List<FlowRule> flow, List<DegradeRule> degrade) {
        List<FlowRule> loadedFlow = FlowRuleManager.getRules();
        List<DegradeRule> loadedDegrade = DegradeRuleManager.getRules();
        if (loadedFlow.size() != flow.size() || loadedDegrade.size() != degrade.size()) {
            throw new FrameworkException("Sentinel 规则下发后回读不一致：flow 期望 " + flow.size()
                    + " 实际 " + loadedFlow.size() + "，degrade 期望 " + degrade.size()
                    + " 实际 " + loadedDegrade.size()
                    + "。厂商的 loadRules 对非法规则的处理是静默丢弃（实测 count=-1 的那条：isValidRule=false、"
                            + "getRules() 少一条、不报错不日志），所以条数比对是唯一看得见"
                            + "“这条规则其实没进去”的手段。请核对上面的校验是否被绕过、以及是否有其它组件"
                            + "在本模块之外重写了这张进程级规则表");
        }
        Set<String> expected = new LinkedHashSet<>();
        flow.forEach(rule -> expected.add(rule.getResource()));
        degrade.forEach(rule -> expected.add(rule.getResource()));
        Set<String> actual = new LinkedHashSet<>();
        loadedFlow.forEach(rule -> actual.add(rule.getResource()));
        loadedDegrade.forEach(rule -> actual.add(rule.getResource()));
        if (!expected.equals(actual)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new LinkedHashSet<>(actual);
            extra.removeAll(expected);
            throw new FrameworkException("Sentinel 规则回读的资源名与下发表不一致：缺少 " + missing
                    + "，多出 " + extra + "（名称应为带前缀的全名，见 SentinelResourceNames）");
        }
    }
}
