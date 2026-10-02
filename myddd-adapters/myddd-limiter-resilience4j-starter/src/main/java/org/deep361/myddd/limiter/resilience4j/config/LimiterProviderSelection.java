package org.deep361.myddd.limiter.resilience4j.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;

import java.util.Set;
import java.util.TreeSet;

/**
 * {@code myddd.limiter.provider} 的判定（Resilience4j 侧那一份）：谁该装配，以及什么时候必须拒绝启动。
 *
 * <p>三条规则与 Task 45 的 Sentinel 侧逐字同形：显式选中本 provider ⇒ 装配；显式选中别人 ⇒ 一个 bean
 * 都不产；没写这一键 ⇒ 只有一份 provider 在 classpath 时按那份装配，两份都在时<b>快速失败</b>——
 * 静默挑一份意味着限流到底按 Sentinel 的秒级跳窗计数还是按 r4j 的令牌桶计数取决于 jar 的加载次序。
 *
 * <p>取值按小写逐字比较，{@code "Resilience4j"} 这类写错大小写的形态落进"选中了别人"那一支
 * （两家都不装配，最终由 Task 44 的"没有端口 bean"诊断收口），而不是由这里再猜一次意图。
 * {@code present} 由调用方给，本类不碰反射。
 */
public final class LimiterProviderSelection {

    /** 配置键（诊断消息的唯一名字来源）。 */
    public static final String KEY = "myddd.limiter.provider";
    /** 本模块对应的取值。 */
    public static final String RESILIENCE4J = "resilience4j";

    private LimiterProviderSelection() {
    }

    /**
     * @param configured {@code myddd.limiter.provider} 的原始值，可为 {@code null} 或空白
     * @param present    classpath 上实际存在的 provider 标识（至少含 {@code resilience4j}）
     * @return 该不该装配本模块
     * @throws FrameworkException 未显式选择且 {@code present} 多于一份（歧义）
     */
    public static boolean shouldAssembleResilience4j(String configured, Set<String> present) {
        String selected = configured == null ? "" : configured.trim();
        if (!selected.isEmpty()) {
            return RESILIENCE4J.equals(selected);
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
