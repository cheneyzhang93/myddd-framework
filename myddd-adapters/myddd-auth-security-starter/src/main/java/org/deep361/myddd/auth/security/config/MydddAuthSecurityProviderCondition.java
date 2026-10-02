package org.deep361.myddd.auth.security.config;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 只在"本模块该装配"时放行：{@code myddd.auth.security.enabled} 与 {@code myddd.auth.provider} 两键一起判。
 *
 * <p>{@code @ConditionalOnProperty} 不是 {@code @Repeatable}（Plan 3 一手实测），所以"provider 自有开关 +
 * 选中标识"没法用两个注解叠在同一类型上；自定义 {@link Condition} 是唯一能同时判两键又不引入第二份
 * 自动配置的做法（§0.3-6）。
 *
 * <p><b>本类不判 {@code myddd.auth.enabled}（壳的总开关）</b>：那一键管的是"拦截要不要发生"，
 * 关掉它之后业务照样可能直接调 {@code AuthPort}（壳自己的 {@code package-info} 就写了这一格），
 * 所以端口实现必须继续在场。把它叠进这里会让"关掉切面"与"换掉实现"变成同一件事。
 * 与 satoken 侧同一条：本类不判厂商类是否在场——那是自动配置类上
 * {@code @ConditionalOnClass(name = ...)} 的活，两格分开各测各的（{@code AC} 组）。
 */
public class MydddAuthSecurityProviderCondition implements Condition {

    /** provider 标识 → marker 类名（<b>字符串</b>，不是依赖，§0.3-11）。 */
    private static final Map<String, String> MARKERS = Map.of(
            AuthSatokenMarker.PROVIDER, AuthSatokenMarker.CLASS,
            AuthSecurityProviderSelection.SECURITY,
            "org.deep361.myddd.auth.security.SpringSecurityAuthPort");

    /**
     * 只装另一家（satoken）的标识与 marker 两个常量。写成具名 holder 而不是内联字面量，是因为
     * {@code Map.of} 的 key 与 value 分两列、两个 provider 的字符串长短不一时，内联写法的对齐会把
     * "哪个 provider 配哪个类"这层对应关系挤掉——本模块没有任何用例读这张表（读到它的只有两家同时
     * 在场那个场合），无测试、无变异的格子只能靠写法自保。
     *
     * <p>⚠️ {@link AuthSatokenMarker#CLASS} 与对方那一家 {@code MARKERS} 里钉我们的
     * {@code org.deep361.myddd.auth.security.SpringSecurityAuthPort} <b>互为反向契约</b>：两边的 FQN
     * 必须与对方交付物逐字一致，否则"两家同时在 ⇒ 启动失败"落不到真 classpath 上（{@code present}
     * 只数出一份 ⇒ 两家都按"唯一那份"装配 ⇒ 两条 {@code AuthPort} bean）。这是本框架唯一一处
     * 跨两个 Maven 模块的字符串契约。
     */
    private static final class AuthSatokenMarker {

        private static final String PROVIDER = "satoken";
        private static final String CLASS = "org.deep361.myddd.auth.satoken.SaTokenAuthPort";

        private AuthSatokenMarker() {
        }
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (!environment.getProperty("myddd.auth.security.enabled", boolean.class, true)) {
            return false;
        }
        return AuthSecurityProviderSelection.shouldAssembleSecurity(
                environment.getProperty(AuthSecurityProviderSelection.KEY), presentProviders(context));
    }

    /**
     * classpath 上有哪些 provider。用 {@code Class.forName(name, false, loader)}——<b>不初始化</b>：
     * 探"在不在场"不该触发对方的静态块。读口名是 {@code getClassLoader()}：spring-context 7.0.9 的
     * {@code ConditionContext} 只有五个方法、没有 {@code getBeanClassLoader}（一手，偏差 D-138）。
     */
    private Set<String> presentProviders(ConditionContext context) {
        ClassLoader classLoader = context.getClassLoader() == null
                ? getClass().getClassLoader() : context.getClassLoader();
        Set<String> present = new TreeSet<>();
        MARKERS.forEach((provider, markerClass) -> {
            try {
                Class.forName(markerClass, false, classLoader);
                present.add(provider);
            } catch (Throwable absent) {
                // 探不到就是不在场。"类不存在"与"链接失败"在这里等价：该 provider 不可用。
            }
        });
        return present;
    }
}
