package org.deep361.myddd.limiter.sentinel.rule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphO;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleUtil;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则的语义校验面（{@code RS6}–{@code RS10}）与下发—回读核验面（{@code RS11}–{@code RS12}）。
 *
 * <p>与读取器测试同放一条"下发—回读"链上：这些用例会真动到 Sentinel 的进程级规则表，
 * 每个改变全局状态的用例第一句都清空两张表，用例之间不靠执行次序。
 */
class SentinelRulePublisherTest {

    private final SentinelRuleValidator validator = new SentinelRuleValidator();

    private MydddSentinelRuleDocument flow(String resource, Double count) {
        return new MydddSentinelRuleDocument(
                List.of(new MydddSentinelRuleDocument.FlowRuleSpec(resource, count)), List.of());
    }

    private MydddSentinelRuleDocument degrade(String resource, String threshold, Double ratio, Double maxRtMs,
            Integer minCalls, Integer windowMs, Integer openSeconds) {
        return new MydddSentinelRuleDocument(List.of(),
                List.of(new MydddSentinelRuleDocument.DegradeRuleSpec(
                        resource, threshold, ratio, maxRtMs, minCalls, windowMs, openSeconds)));
    }

    private void clearVendorTables() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }

    /**
     * {@code RS6}：count 缺失/负/NaN/Infinity 全被校验拒；厂商对负数规则确实静默丢弃。
     *
     * <p><b>{@code count=0} 单独一条断言，而且方向相反</b>：一手实测（{@code ProbeRS6} 的 B/E 格）
     * 它 {@code isValidRule=true}、{@code getRules()} 仍是 1 条、第一次 {@code entry=false}——
     * 是厂商的零配额开关，不是被丢掉的哑规则。计划里"0 会被丢弃、于是全放行"那句读的正是这一格，
     * 与厂商行为相反 ⇒ 校验器放行 0，这里把它钉成"放行 + 真拦"。
     */
    @Test
    void should_reject_a_flow_count_that_the_vendor_would_drop() {
        List<Double> bad = List.of(-1.0D, Double.NaN, Double.POSITIVE_INFINITY);
        assertThatThrownBy(() -> validator.validate(flow("a", null)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("flow[0]").hasMessageContaining("count");
        for (Double count : bad) {
            assertThatThrownBy(() -> validator.validate(flow("a", count)))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("flow[0]")
                    .hasMessageContaining("resource=a")
                    .hasMessageContaining("count=")
                    .hasMessageContaining("丢掉");
        }
        assertThatCode(() -> validator.validate(flow("a", 0.0D))).doesNotThrowAnyException();

        clearVendorTables();
        FlowRule negative = new FlowRule();
        negative.setResource("probe-rs6-negative");
        negative.setCount(-1D);
        assertThat(FlowRuleUtil.isValidRule(negative)).isFalse();
        FlowRuleManager.loadRules(List.of(negative));
        assertThat(FlowRuleManager.getRules()).isEmpty();

        FlowRule zero = new FlowRule();
        zero.setResource("probe-rs6-zero");
        zero.setCount(0D);
        assertThat(FlowRuleUtil.isValidRule(zero)).isTrue();
        FlowRuleManager.loadRules(List.of(zero));
        assertThat(FlowRuleManager.getRules()).hasSize(1);
        assertThat(SphO.entry("probe-rs6-zero", EntryType.IN, 1)).isFalse();
        clearVendorTables();
    }

    /** {@code RS7}：exception-count 形态点名实测结论；RT/大写/ratio/null/空 都拒且给出两个可取值。 */
    @Test
    void should_reject_the_exception_count_form_and_name_the_measurement() {
        assertThatThrownBy(() -> validator.validate(degrade("c", "exception-count", 0.5D, null, 3, 3000, 10)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("仍然放行");
        for (String bad : new String[] {"RT", "ratio", null, ""}) {
            assertThatThrownBy(() -> validator.validate(degrade("c", bad, 0.5D, null, 3, 3000, 10)))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining(SentinelRuleValidator.THRESHOLD_EXCEPTION_RATIO)
                    .hasMessageContaining(SentinelRuleValidator.THRESHOLD_RT);
        }
    }

    /** {@code RS8}：rt 形态两个值齐全；多余 maxRtMs（占比形态下）也拒。 */
    @Test
    void should_require_both_values_of_the_rt_form_and_never_a_stray_maxRtMs() {
        assertThatThrownBy(() -> validator.validate(degrade("c", "rt", 0.5D, null, 3, 3000, 10)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("哑规则");
        assertThatThrownBy(() -> validator.validate(degrade("c", "rt", 0.5D, 0.0D, 3, 3000, 10)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("maxRtMs");
        assertThatThrownBy(() -> validator.validate(
                degrade("c", SentinelRuleValidator.THRESHOLD_EXCEPTION_RATIO, 0.5D, 500.0D, 3, 3000, 10)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("未使用字段忽略");
    }

    /** {@code RS9}：百分数 ratio、越界 ratio、minCalls/openSeconds/windowMs 各拒；ratio=1.0 边界收。 */
    @Test
    void should_reject_a_ratio_that_is_a_percentage_and_a_window_below_one_second() {
        assertThatThrownBy(() -> validator.validate(degrade("c", "exception-ratio", 50.0D, null, 3, 3000, 10)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("5000%");
        assertThatThrownBy(() -> validator.validate(degrade("c", "exception-ratio", 0.0D, null, 3, 3000, 10)))
                .isInstanceOf(FrameworkException.class).hasMessageContaining("ratio");
        assertThatThrownBy(() -> validator.validate(degrade("c", "exception-ratio", 1.2D, null, 3, 3000, 10)))
                .isInstanceOf(FrameworkException.class).hasMessageContaining("ratio");
        assertThatThrownBy(() -> validator.validate(degrade("c", "exception-ratio", 0.5D, null, 0, 3000, 10)))
                .isInstanceOf(FrameworkException.class).hasMessageContaining("最小调用数");
        assertThatThrownBy(() -> validator.validate(degrade("c", "exception-ratio", 0.5D, null, 3, 3000, 0)))
                .isInstanceOf(FrameworkException.class).hasMessageContaining("开闸期");
        assertThatThrownBy(() -> validator.validate(degrade("c", "exception-ratio", 0.5D, null, 3, 500, 10)))
                .isInstanceOf(FrameworkException.class).hasMessageContaining("1000ms");
        assertThatCode(() -> validator.validate(degrade("c", "exception-ratio", 1.0D, null, 3, 3000, 10)))
                .doesNotThrowAnyException();
    }

    /** {@code RS10}：同段重名拒、跨段同名合法。 */
    @Test
    void should_reject_a_duplicated_resource_inside_one_section_only() {
        MydddSentinelRuleDocument dupFlow = new MydddSentinelRuleDocument(
                List.of(new MydddSentinelRuleDocument.FlowRuleSpec("x", 1.0D),
                        new MydddSentinelRuleDocument.FlowRuleSpec("x", 2.0D)),
                List.of());
        assertThatThrownBy(() -> validator.validate(dupFlow))
                .isInstanceOf(FrameworkException.class).hasMessageContaining("x");

        MydddSentinelRuleDocument dupDegrade = new MydddSentinelRuleDocument(List.of(),
                List.of(new MydddSentinelRuleDocument.DegradeRuleSpec("y", "exception-ratio", 0.5D, null, 3, 3000, 10),
                        new MydddSentinelRuleDocument.DegradeRuleSpec("y", "exception-ratio", 0.6D, null, 3, 3000, 10)));
        assertThatThrownBy(() -> validator.validate(dupDegrade))
                .isInstanceOf(FrameworkException.class).hasMessageContaining("y");

        MydddSentinelRuleDocument crossSection = new MydddSentinelRuleDocument(
                List.of(new MydddSentinelRuleDocument.FlowRuleSpec("z", 1.0D)),
                List.of(new MydddSentinelRuleDocument.DegradeRuleSpec("z", "exception-ratio", 0.5D, null, 3, 3000, 10)));
        assertThatCode(() -> validator.validate(crossSection)).doesNotThrowAnyException();
    }

    private MydddSentinelRuleDocument validDocument() {
        return new MydddSentinelRuleDocument(
                List.of(new MydddSentinelRuleDocument.FlowRuleSpec("order:create", 50.0D),
                        new MydddSentinelRuleDocument.FlowRuleSpec("order:query", 200.0D)),
                List.of(
                        new MydddSentinelRuleDocument.DegradeRuleSpec("payment:capture",
                                "exception-ratio", 0.5D, null, 20, 5000, 10),
                        new MydddSentinelRuleDocument.DegradeRuleSpec("inventory:lock",
                                "rt", 0.6D, 500.0D, 10, 3000, 15)));
    }

    /** {@code RS11}：下发后回读厂商对象逐字段核验；绕过校验直投非法规则被厂商丢弃；reload 把表修回来。 */
    @Test
    void should_verify_the_published_table_by_reading_it_back() {
        clearVendorTables();
        SentinelRulePublisher publisher = new SentinelRulePublisher(this::validDocument);
        assertThat(publisher.publish(validDocument())).isEqualTo(4);

        Map<String, FlowRule> flow = FlowRuleManager.getRules().stream()
                .collect(Collectors.toMap(FlowRule::getResource, r -> r));
        assertThat(flow).containsOnlyKeys("myddd:rate:order:create", "myddd:rate:order:query");
        assertThat(flow.get("myddd:rate:order:create").getGrade()).isEqualTo(RuleConstant.FLOW_GRADE_QPS);
        assertThat(flow.get("myddd:rate:order:create").getCount()).isEqualTo(50.0D);
        assertThat(flow.get("myddd:rate:order:query").getCount()).isEqualTo(200.0D);

        Map<String, DegradeRule> degrade = new HashMap<>();
        for (DegradeRule rule : DegradeRuleManager.getRules()) {
            degrade.put(rule.getResource(), rule);
        }
        assertThat(degrade).containsOnlyKeys("myddd:circuit:payment:capture", "myddd:circuit:inventory:lock");
        DegradeRule ratio = degrade.get("myddd:circuit:payment:capture");
        assertThat(ratio.getStatIntervalMs()).isEqualTo(5000);
        assertThat(ratio.getMinRequestAmount()).isEqualTo(20);
        assertThat(ratio.getTimeWindow()).isEqualTo(10);
        assertThat(ratio.getCount()).isEqualTo(0.5D);
        DegradeRule rt = degrade.get("myddd:circuit:inventory:lock");
        assertThat(rt.getCount()).isEqualTo(500.0D);
        assertThat(rt.getStatIntervalMs()).isEqualTo(3000);
        assertThat(rt.getMinRequestAmount()).isEqualTo(10);
        assertThat(rt.getTimeWindow()).isEqualTo(15);

        FlowRule dropped = new FlowRule();
        dropped.setResource("myddd:rate:sneaky");
        dropped.setCount(-1D);
        FlowRuleManager.loadRules(List.of(dropped));
        assertThat(FlowRuleUtil.isValidRule(dropped)).isFalse();
        assertThat(FlowRuleManager.getRules()).isEmpty();

        assertThat(publisher.reload()).isEqualTo(4);
    }

    /** {@code RS12}：同裸名在两段下发成两条前缀资源；前缀点是纯拼接、不 trim（两个裸名各成一条）。 */
    @Test
    void should_publish_the_same_plain_name_in_both_sections_as_two_resources() {
        clearVendorTables();
        SentinelRulePublisher publisher = new SentinelRulePublisher(() -> MydddSentinelRuleDocument.EMPTY);

        MydddSentinelRuleDocument sameName = new MydddSentinelRuleDocument(
                List.of(new MydddSentinelRuleDocument.FlowRuleSpec("same-biz", 1.0D)),
                List.of(new MydddSentinelRuleDocument.DegradeRuleSpec("same-biz", "exception-ratio",
                        0.5D, null, 2, 3000, 10)));
        assertThatCode(() -> validator.validate(sameName)).doesNotThrowAnyException();
        assertThat(publisher.publish(sameName)).isEqualTo(2);
        Set<String> names = FlowRuleManager.getRules().stream().map(FlowRule::getResource)
                .collect(Collectors.toSet());
        names.addAll(DegradeRuleManager.getRules().stream().map(DegradeRule::getResource)
                .collect(Collectors.toSet()));
        assertThat(names).containsExactlyInAnyOrder("myddd:rate:same-biz", "myddd:circuit:same-biz");

        MydddSentinelRuleDocument trimCheck = new MydddSentinelRuleDocument(
                List.of(new MydddSentinelRuleDocument.FlowRuleSpec("trim-check", 7.0D),
                        new MydddSentinelRuleDocument.FlowRuleSpec(" trim-check", 9.0D)),
                List.of());
        assertThat(publisher.publish(trimCheck)).isEqualTo(2);
        Map<String, Double> counts = new HashMap<>();
        for (FlowRule rule : FlowRuleManager.getRules()) {
            counts.put(rule.getResource(), rule.getCount());
        }
        assertThat(counts)
                .containsEntry("myddd:rate:trim-check", 7.0D)
                .containsEntry("myddd:rate: trim-check", 9.0D);
    }
}
