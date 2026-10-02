package org.deep361.myddd.job.spring.config;

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
 * <p>{@code @ConditionalOnProperty} 不是 {@code @Repeatable}（Plan 3 一手实测，偏差台账里那条），
 * 所以"总开关 + 选中标识"两件事没法用两个注解叠在同一类型上——自定义 {@link Condition} 是唯一能同时
 * 判两键又不引入第二份自动配置的做法（§0.3-6：一个模块一份自动配置）。
 */
public class MydddJobProviderCondition implements Condition {

    /**
     * provider 标识 → marker 类名。value 是<b>字符串</b>而不是 {@code XxlJobPort.class}：
     * §0.3-11 与 R1 都不允许 provider 模块相互依赖，一张字符串表是"无壳能力"上做互斥判定的唯一不越界写法。
     */
    private static final Map<String, String> MARKERS = Map.of(
            JobProviderSelection.SPRING, "org.deep361.myddd.job.spring.SpringJobPort",
            "xxljob", "org.deep361.myddd.job.xxljob.XxlJobPort");

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        if (!environment.getProperty("myddd.job.enabled", boolean.class, true)) {
            return false;
        }
        return JobProviderSelection.shouldAssembleSpring(
                environment.getProperty(JobProviderSelection.KEY), presentProviders(context));
    }

    /**
     * classpath 上有哪些 provider。用 {@code Class.forName(name, false, loader)}——<b>不初始化</b>：
     * 探"在不在场"不该触发对方的静态块（日志、线程）。本模块自己在场是事实（这个类就在本 jar 里），
     * 所以表里 {@code spring} 那一行其实永远命中，留着是为了让这张表读起来就是完整的一张。
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
