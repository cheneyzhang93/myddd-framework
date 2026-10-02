package org.deep361.myddd.limiter.resilience4j.rule;

import java.util.ArrayList;
import java.util.List;

import org.deep361.myddd.kernel.common.error.FrameworkException;

/**
 * 文档级契约校验：只拦"r4j 认为合法但端口用不了"的形态。四段诊断 = 段名 + 下标 + 资源名 + 字段实际值与期望形态。
 *
 * <p><b>为什么这里没有数值区间检查</b>：{@code limitForPeriod<=0}、{@code failureRateThreshold} 越界、
 * {@code waitDurationInOpenState=0} 等厂商都在 {@code build()} 当场抛 {@code IllegalArgumentException}
 * （实测消息见各方法 javadoc，零/负配额那一组读数在 {@code ProbeR4jQuota}）⇒ 重复实现等于第二个真源。
 * 越界值由 {@code Resilience4jRulePublisher} 翻译成 {@code FrameworkException} 并补上段名 + 下标 + 资源名，
 * 本类只负责拦下面四种"能 build 出来但语义不对"的形态。
 */
public final class Resilience4jRuleValidator {

    /** 与 Sentinel 侧同名的两种阈值形态；r4j 侧只有这两种可表达（{@code error-count} 与 {@code rt} 是差集）。 */
    public static final String ERROR_RATIO = "error-ratio";
    /** 慢调用比例：= {@code slowCallRateThreshold} + {@code slowCallDurationThreshold(maxRtMs)}。 */
    public static final String SLOW_RATIO = "slow-ratio";
    /** 窗口毫秒必须整秒的除数（TIME_BASED 的 {@code slidingWindowSize} 单位是<b>秒</b>）。 */
    private static final long MILLIS_PER_SECOND = 1000L;

    private Resilience4jRuleValidator() {
    }

    /** 校验并返回可读的告警列表（目前只有"同资源重复"这一条会进告警而不是报错）。 */
    public static List<String> validate(MydddResilience4jRuleDocument document) {
        List<String> problems = new ArrayList<>();
        checkFlow(document, problems);
        checkDegrade(document, problems);
        if (!problems.isEmpty()) {
            throw new FrameworkException("Resilience4j 规则文档未通过契约校验：" + String.join("；", problems)
                    + "。这一层的判据是「r4j 能 build 出来但我们的端口形状用不了」，"
                    + "厂商自己会拒的越界值不在这里重复（见类 javadoc）");
        }
        return List.of();
    }

    private static void checkFlow(MydddResilience4jRuleDocument document, List<String> problems) {
        for (int i = 0; i < document.getFlow().size(); i++) {
            var entry = document.getFlow().get(i);
            String where = "flow[" + i + "]（资源 " + entry.getResource() + "）";
            if (entry.getCount() == null) {
                problems.add(where + " 缺 count：期望每个 1 秒刷新周期放行的许可数（整数），"
                        + "缺省不是「不限流」而是 r4j 的 build() 抛 IllegalArgumentException");
            }
        }
    }

    private static void checkDegrade(MydddResilience4jRuleDocument document, List<String> problems) {
        for (int i = 0; i < document.getDegrade().size(); i++) {
            var entry = document.getDegrade().get(i);
            String where = "degrade[" + i + "]（资源 " + entry.getResource() + "）";
            String threshold = entry.getThreshold();
            if (!ERROR_RATIO.equals(threshold) && !SLOW_RATIO.equals(threshold)) {
                problems.add(where + " threshold=" + threshold + "：r4j 侧只接受 " + ERROR_RATIO + " 或 "
                        + SLOW_RATIO + "。error-count 与 rt 是 Sentinel 侧独有的形态（那里是"
                        + "「异常次数」与「平均 RT」两条独立判据），在这里没有对应配置项，"
                        + "硬翻会变成「写了却没生效」");
            }
            if (entry.getRatio() == null) {
                problems.add(where + " 缺 ratio（0~100 的百分比，开区间）");
            } else if (entry.getRatio() >= 100F) {
                problems.add(where + " ratio=" + entry.getRatio() + "：r4j 的阈值必须 <100（实测消息 "
                        + "failureRateThreshold must be greater than 0 and lower than 100），"
                        + "而 Sentinel 侧接受等价的 1.0 ⇒ 这是一条真不对称，见偏差 D-161 与 Task 47 的清单，"
                        + "本处不许偷偷换算成 99.99");
            }
            if (SLOW_RATIO.equals(threshold) && (entry.getMaxRtMs() == null || entry.getMaxRtMs() <= 0)) {
                problems.add(where + " threshold=slow-ratio 却缺 maxRtMs：慢调用阈值 = "
                        + "slowCallDurationThreshold，没有它整条规则退化成「所有调用都算慢」");
            }
            long windowMs = entry.getWindowMs() == null ? MILLIS_PER_SECOND : entry.getWindowMs();
            if (windowMs % MILLIS_PER_SECOND != 0) {
                problems.add(where + " windowMs=" + windowMs + "：TIME_BASED 窗口的单位是秒，非整秒会被"
                        + "向下取整成 " + (windowMs / MILLIS_PER_SECOND) + " 秒（windowMs<1000 时实测直接抛 "
                        + "IllegalArgumentException: slidingWindowSize must be greater than 0）⇒ 拒收而不是静默取整");
            }
            if (entry.getMinCalls() != null && entry.getMinCalls() <= 0) {
                problems.add(where + " minCalls=" + entry.getMinCalls() + "：最小调用数必须是正数");
            }
        }
    }
}
