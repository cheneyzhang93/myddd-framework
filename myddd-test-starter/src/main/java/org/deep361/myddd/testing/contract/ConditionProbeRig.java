package org.deep361.myddd.testing.contract;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用一个"什么都不在 classpath 上"的假 classloader 驱动一次 {@link Condition} 判定，并记下它问过的每个类名。
 *
 * <p><b>为什么需要它</b>：两份条件类各自持有一张 {@code MARKERS}（provider 标识 → 对方 port 实现类的
 * 全限定名），写错的代价是"两份自动装配同时产出"（§0.3-6），而这件事在单侧测试里<b>不可观察</b>——
 * 本机 classpath 上永远只有本侧那一份。问过的类名就是 marker：这是唯一能在<b>不读私有字段</b>的前提下
 * 拿到生产探测字符串的办法，所以 {@code EQ3} 钉的是生产代码而不是测试里的副本。
 */
public final class ConditionProbeRig {

    /** 判定结果与探测面。 */
    public record Probe(boolean matched, Set<String> probedClassNames) {
    }

    /**
     * @param key           选中标识的配置键（两份 {@code JobProviderSelection.KEY} 同值）
     * @param configuredValue {@code null} 表示"这一键没写"；此时两份条件都走"present 里只有一份也没有"那一支，不会抛
     */
    public static Probe probe(Condition condition, String key, String configuredValue) {
        RecordingClassLoader loader = new RecordingClassLoader();
        MockEnvironment environment = new MockEnvironment();
        if (configuredValue != null) {
            environment.setProperty(key, configuredValue);
        }
        return new Probe(condition.matches(new StubContext(loader, environment), null), loader.asked());
    }

    private ConditionProbeRig() {
    }

    /** 一律回答"不在"，但把问过的名字留下——{@code Class.forName(name, false, loader)} 走的就是这条路。 */
    private static final class RecordingClassLoader extends ClassLoader {

        private final Set<String> asked = new LinkedHashSet<>();

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            asked.add(name);
            throw new ClassNotFoundException(name);
        }

        synchronized Set<String> asked() {
            return Set.copyOf(asked);
        }
    }

    /** 五个方法里只有两个真被用到；其余返回 {@code null}——本任务的判据不经过它们。 */
    private record StubContext(ClassLoader classLoader,
                               Environment environment) implements ConditionContext {

        @Override
        public BeanDefinitionRegistry getRegistry() {
            return null;
        }

        @Override
        public ConfigurableListableBeanFactory getBeanFactory() {
            return null;
        }

        @Override
        public Environment getEnvironment() {
            return environment;
        }

        @Override
        public ResourceLoader getResourceLoader() {
            return null;
        }

        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }
    }
}
