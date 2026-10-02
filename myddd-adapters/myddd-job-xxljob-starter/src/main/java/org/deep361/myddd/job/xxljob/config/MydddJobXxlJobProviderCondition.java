package org.deep361.myddd.job.xxljob.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 只在"本模块该装配"时放行的条件：{@code myddd.job.enabled} 与 {@code myddd.job.provider} 两键一起判。
 *
 * <p>为什么是自定义 {@link Condition} 而不是两个 {@code @ConditionalOnProperty}：后者不是
 * {@code @Repeatable}（Plan 3 一手实测），"总开关 + 选中标识"两件事没法叠在同一类型上，而 §0.3-6 只许
 * 本模块有一份自动配置——把开关拆成第二个自动配置类去承载就是绕开这条规矩。
 *
 * <p>与 spring 侧那份 {@code MydddJobProviderCondition} 同形，唯一差别是 {@link #MARKERS} 里谁是"自己"：
 * 两侧的 {@code present} 集合都必须是同一张完整表，否则"另一家在不在场"这件事在一侧看得见、另一侧看不见，
 * 歧义快速失败就只在半个能力上生效（{@code PS4} 在两侧各钉一条同输入同答复）。
 */
public class MydddJobXxlJobProviderCondition implements Condition {

    /** provider 标识 → marker 类名（<b>字符串</b>，不是依赖）。 */
    private static final Map<String, String> MARKERS = Map.of(
            JobProviderSelection.XXLJOB, "org.deep361.myddd.job.xxljob.XxlJobPort",
            "spring", "org.deep361.myddd.job.spring.SpringJobPort");

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (!environment.getProperty("myddd.job.enabled", boolean.class, true)) {
            return false;
        }
        return JobProviderSelection.shouldAssembleXxljob(
                environment.getProperty(JobProviderSelection.KEY), presentProviders(context));
    }

    /**
     * classpath 上有哪些 provider。用 {@code Class.forName(name, false, loader)}——<b>不初始化</b>：
     * 探"在不在场"不该触发对方的静态块。本模块自己在场是事实（这个类就在本 jar 里），表里 {@code xxljob}
     * 那一行永远命中，留着是为了让这张表读起来就是完整的一张。
     */
    private Set<String> presentProviders(ConditionContext context) {
        // 读口名是 getClassLoader()：一手核对 spring-context 7.0.9（javap 显示这个接口只有
        // getRegistry / getBeanFactory / getEnvironment / getResourceLoader / getClassLoader 五个方法，
        // 没有 getBeanClassLoader——写成后者整模块编译不过，见偏差 D-138）。
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
