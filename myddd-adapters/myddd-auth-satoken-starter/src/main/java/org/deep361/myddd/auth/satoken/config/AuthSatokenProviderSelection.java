package org.deep361.myddd.auth.satoken.config;

import java.util.Set;
import java.util.TreeSet;
import org.deep361.myddd.kernel.common.error.FrameworkException;

/**
 * {@code myddd.auth.provider} 的判定：本 provider 该不该装配（§0.3-6 的"鉴权互斥"字面形态）。
 *
 * <p>三条规则与 Job（Task 41/42）、limiter（Task 45/46）两侧<b>逐字相同</b>：
 * <ol>
 *   <li><b>显式选中本 provider</b> ⇒ 装配。</li>
 *   <li><b>显式选中别人</b> ⇒ 本模块一个 bean 都不产（两个 provider 同时在 classpath 是合法形态）。</li>
 *   <li><b>没写这一键</b> ⇒ 只有一份 provider 在 classpath 时按那份装配；两份都在时<b>快速失败</b>
 *       ——鉴权这一格静默挑一份，意味着同一份业务代码在两个部署里走两套会话机制。</li>
 * </ol>
 *
 * <p><b>为什么这份判定函数在 provider 里再镜像一遍而不是从壳里借</b>：auth 这条线上壳与 provider
 * 之间没有 compile 依赖边（D-176 那张表），壳递不出它，而 §0.3-11 又禁 provider↔provider ⇒ 没有第三个
 * 合法落点。这条重复是<b>有意</b>的，与 limiter 侧同一处理（{@code 45d}/{@code 46d}），
 * 由 Task 51 的等价性用例钉"两份镜像给出同一答案"（那里两家同时在场，是唯一能同时看到两份的场合）。
 *
 * <p>取值按小写逐字比较：{@code "SaToken"} 这类大小写写错落进第 2 条（"选中了别人"），两家都不装配，
 * 最终由 Task 48 的 {@code AuthPortGuard}（"一个 {@code AuthPort} 都没有"）收口成可读诊断，
 * 而不是由这里再猜一次意图。{@code present} 由调用方给，本类不碰反射。
 */
public final class AuthSatokenProviderSelection {

    /** 配置键（两家共用同一个键名，字符串各自镜像）。 */
    public static final String KEY = "myddd.auth.provider";
    /** 本模块对应的取值。 */
    public static final String SATOKEN = "satoken";

    private AuthSatokenProviderSelection() {
    }

    /**
     * @param configured {@code myddd.auth.provider} 的原始值，可为 {@code null} 或空白
     * @param present    classpath 上实际存在的 provider 标识（至少含 {@code satoken}）
     * @return 该不该装配本模块
     * @throws FrameworkException 未显式选择且 {@code present} 多于一份（歧义）
     */
    public static boolean shouldAssembleSatoken(String configured, Set<String> present) {
        String selected = configured == null ? "" : configured.trim();
        if (!selected.isEmpty()) {
            return SATOKEN.equals(selected);
        }
        Set<String> known = new TreeSet<>(present);
        if (known.size() <= 1) {
            return true;
        }
        throw new FrameworkException(KEY + " 必填（classpath 上同时有 " + known
                + "，期望值：satoken 或 security）——同一能力只许一份自动装配产出（§0.3-6）。"
                + "不写这一键时框架按 classpath 上唯一的那份装配，两份都在就必须由人来指认。");
    }
}
