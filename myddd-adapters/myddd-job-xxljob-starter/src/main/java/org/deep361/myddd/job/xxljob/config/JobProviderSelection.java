package org.deep361.myddd.job.xxljob.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;

import java.util.Set;
import java.util.TreeSet;

/**
 * {@code myddd.job.provider} 在 XXL-Job 侧的判定：本模块该不该装配。
 *
 * <p><b>与 spring 侧那份的关系</b>：同名、不同包（{@code …job.spring.config} 与 {@code …job.xxljob.config}），
 * 三条规则逐字相同。这不是"重复代码被放过"，而是 Job 能力没有装配壳（R1 只批准了 mq/resilience/auth 三个）时
 * §0.3-6"同一能力只许一份自动装配产出"的<b>唯一</b>落法：判定必须各自持有一份，两份都在 classpath 上时
 * 才有东西可互斥。包名不同，所以两个 jar 共存时类型不冲突；行为一致性由 Task 43 的 {@code EQ} 组跨模块钉
 * （那里比的是"两侧对同一个输入给出同一个答复"，不是比源码文本）。
 * <b>改任何一侧的三条规则，必须同时改另一侧。</b>
 *
 * <p>三条规则：
 * <ol>
 *   <li><b>显式选中本 provider</b>（{@code provider=xxljob}）⇒ 装配。</li>
 *   <li><b>显式选中别人</b>（{@code provider=spring}）⇒ 本模块一个 bean 都不产，而且不算失败：
 *       两个 provider 同时在 classpath 是合法形态，选中的那一个负责装配。</li>
 *   <li><b>没写这一键</b> ⇒ classpath 上只有一份 provider 时按那一份装配（"单体应用引一侧就好"的零配置出路）；
 *       两份都在时<b>快速失败</b>——静默挑一份意味着"本地 cron 生效还是调度中心下发生效"取决于 jar 的加载次序。</li>
 * </ol>
 *
 * <p>{@code present} 由调用方给（{@link MydddJobXxlJobProviderCondition} 用类加载器探），本类不碰反射：
 * 判定是纯的，于是 {@code PS} 组五条不需要把 spring 那一侧摆进测试 classpath。
 */
public final class JobProviderSelection {

    /** 配置键（诊断消息的唯一名字来源，与 spring 侧逐字同一串）。 */
    public static final String KEY = "myddd.job.provider";
    /** 本模块对应的取值。 */
    public static final String XXLJOB = "xxljob";

    private JobProviderSelection() {
    }

    /**
     * @param configured {@code myddd.job.provider} 的原始值，可为 {@code null} 或空白
     * @param present    classpath 上实际存在的 provider 标识（至少含 {@code xxljob}）
     * @return 该不该装配本模块
     * @throws FrameworkException 未显式选择且 {@code present} 多于一份（歧义）
     */
    public static boolean shouldAssembleXxljob(String configured, Set<String> present) {
        String selected = configured == null ? "" : configured.trim();
        if (!selected.isEmpty()) {
            return XXLJOB.equals(selected);
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
