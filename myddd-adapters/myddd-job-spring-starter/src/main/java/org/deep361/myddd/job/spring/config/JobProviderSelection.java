package org.deep361.myddd.job.spring.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;

import java.util.Set;
import java.util.TreeSet;

/**
 * {@code myddd.job.provider} 的判定：谁该装配，以及什么时候必须拒绝启动。
 *
 * <p>三条规则（§0.3-6 在"无装配壳"能力上的字面形态）：
 * <ol>
 *   <li><b>显式选中本 provider</b> ⇒ 装配。</li>
 *   <li><b>显式选中别人</b> ⇒ 本模块一个 bean 都不产（不是失败：两个 provider 同时在 classpath 是合法形态，
 *       选中的那一个负责装配）。</li>
 *   <li><b>没写这一键</b> ⇒ 只有一份 provider 在 classpath 时按那份装配（"单体应用引一侧就好"的零配置出路）；
 *       两份都在时<b>快速失败</b>——静默挑一份意味着本地调度与远程调度谁生效取决于 jar 的加载次序。</li>
 * </ol>
 *
 * <p>{@code present} 由调用方给（{@link MydddJobProviderCondition} 用类加载器探），
 * 本类不碰反射：判定是纯的，所以 {@code PS} 组五条不需要真把 xxljob 摆上 classpath。
 */
public final class JobProviderSelection {

    /** 配置键（诊断消息的唯一名字来源）。 */
    public static final String KEY = "myddd.job.provider";
    /** 本模块对应的取值。 */
    public static final String SPRING = "spring";

    private JobProviderSelection() {
    }

    /**
     * @param configured {@code myddd.job.provider} 的原始值，可为 {@code null} 或空白
     * @param present    classpath 上实际存在的 provider 标识（至少含 {@code spring}）
     * @return 该不该装配本模块
     * @throws FrameworkException 未显式选择且 {@code present} 多于一份（歧义）
     */
    public static boolean shouldAssembleSpring(String configured, Set<String> present) {
        String selected = configured == null ? "" : configured.trim();
        if (!selected.isEmpty()) {
            return SPRING.equals(selected);
        }
        Set<String> known = new TreeSet<>(present);
        if (known.size() <= 1) {
            return true;
        }
        throw new FrameworkException(KEY + " 必填（classpath 上同时有 " + known
                + "，期望值：spring 或 xxljob）——同一能力只许一份自动装配产出（§0.3-6）。"
                + "不写这一键时框架按 classpath 上唯一的那份装配，两份都在就必须由人来指认。");
    }
}
