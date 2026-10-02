package org.deep361.myddd.limiter.sentinel.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;

import java.util.Set;
import java.util.TreeSet;

/**
 * {@code myddd.limiter.provider} 的判定：谁该装配，以及什么时候必须拒绝启动（偏差 D-150 补入的键）。
 *
 * <p>三条规则（§0.3-6 在无装配壳能力上的字面形态，与 Job 侧同一条）：
 * <ol>
 *   <li><b>显式选中本 provider</b> ⇒ 装配。</li>
 *   <li><b>显式选中别人</b> ⇒ 本模块一个 bean 都不产（不是失败：两个 provider 同时在 classpath 是
 *       合法形态，选中的那一个负责装配）。</li>
 *   <li><b>没写这一键</b> ⇒ 只有一份 provider 在 classpath 时按那份装配（"单体应用引一侧就好"的
 *       零配置出路）；两份都在时<b>快速失败</b>——静默挑一份意味着限流到底按 QPS 计数还是按
 *       r4j 的令牌桶计数取决于 jar 的加载次序。</li>
 * </ol>
 *
 * <p>取值按小写逐字比较，{@code "Sentinel"} 这类写错大小值的形态落进第 2 条（"选中了别人"）：
 * 两家都不装配，最终由 Task 44 的"没有端口 bean"诊断收口（{@code RL14}/{@code AC6}），
 * 而不是由这里再猜一次意图。{@code present} 由调用方给，本类不碰反射。
 */
public final class LimiterProviderSelection {

    /** 配置键（诊断消息的唯一名字来源）。 */
    public static final String KEY = "myddd.limiter.provider";
    /** 本模块对应的取值。 */
    public static final String SENTINEL = "sentinel";

    private LimiterProviderSelection() {
    }

    /**
     * @param configured {@code myddd.limiter.provider} 的原始值，可为 {@code null} 或空白
     * @param present    classpath 上实际存在的 provider 标识（至少含 {@code sentinel}）
     * @return 该不该装配本模块
     * @throws FrameworkException 未显式选择且 {@code present} 多于一份（歧义）
     */
    public static boolean shouldAssembleSentinel(String configured, Set<String> present) {
        String selected = configured == null ? "" : configured.trim();
        if (!selected.isEmpty()) {
            return SENTINEL.equals(selected);
        }
        Set<String> known = new TreeSet<>(present);
        if (known.size() <= 1) {
            return true;
        }
        throw new FrameworkException(KEY + " 必填（classpath 上同时有 " + known
                + "，期望值：sentinel 或 resilience4j）——同一能力只许一份自动装配产出（§0.3-6）。"
                + "不写这一键时框架按 classpath 上唯一的那份装配，两份都在就必须由人来指认。");
    }
}
