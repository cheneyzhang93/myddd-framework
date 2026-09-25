package org.deep361.myddd.domain.context;

import org.deep361.myddd.kernel.ddd.context.Domain;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code @Domain} 领域模块扫描器：在给定基础包（含子包）下找出全部被 {@code @Domain} 标注的类。
 *
 * <p>支持组合注解：{@code @Domain} 标在自定义注解上，使用该自定义注解的类即为模块入口；组合注解的声明本身
 * 不是入口（它是注解类型，作为声明载体被跳过）。
 *
 * <p>扫描结果按模块名升序 —— 装配结果必须确定，否则"哪个模块先装配"会随 classpath 顺序漂移。
 *
 * <p><b>失败即启动失败</b>：基础包为空/空白、模块名为空白、{@code @Domain.scanPackages} 含空白项、
 * {@code @Domain} 标在接口/抽象类/非静态内部类上，一律抛异常。
 */
public class DomainModuleScanner {

    private final List<String> basePackages;

    /**
     * @param basePackages 扫描基础包（含子包），不可为空集合、不可含 null 或空白项；重复项自动去重
     */
    public DomainModuleScanner(Collection<String> basePackages) {
        Objects.requireNonNull(basePackages, "basePackages 不能为 null");
        if (basePackages.isEmpty()) {
            throw new IllegalArgumentException("basePackages 不能为空");
        }
        List<String> normalized = new ArrayList<>();
        for (String candidate : basePackages) {
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalArgumentException("basePackages 不能包含 null 或空白项：" + basePackages);
            }
            String trimmed = candidate.trim();
            if (!normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        this.basePackages = List.copyOf(normalized);
    }

    /** 归一化后的扫描基础包（去重、去空白）。 */
    public List<String> basePackages() {
        return basePackages;
    }

    /** 扫描并返回按模块名升序排列的领域模块。 */
    public List<DomainModule> scan() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // 候选判定交回本类：Spring 默认会静默丢弃接口/抽象类/非静态内部类声明，这里改为显式失败
                        return true;
                    }
                };
        provider.addIncludeFilter(new AnnotationTypeFilter(Domain.class));

        Set<String> seen = new LinkedHashSet<>();
        List<DomainModule> modules = new ArrayList<>();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : provider.findCandidateComponents(basePackage)) {
                String className = candidate.getBeanClassName();
                if (className == null || !seen.add(className)) {
                    continue;
                }
                Class<?> source = ClassUtils.resolveClassName(className, ClassUtils.getDefaultClassLoader());
                if (source.isAnnotation()) {
                    continue;
                }
                assertModuleEntry(source);
                modules.add(toModule(source));
            }
        }
        modules.sort(Comparator.comparing(DomainModule::name));
        return List.copyOf(modules);
    }

    /** 模块入口必须是具体且独立的类；接口/抽象类/非静态内部类在此失败，而不是被候选过滤静默丢弃。 */
    private static void assertModuleEntry(Class<?> source) {
        if (source.isInterface()) {
            throw new IllegalStateException("@" + Domain.class.getSimpleName() + " 不能标注在接口上："
                    + source.getName());
        }
        if (Modifier.isAbstract(source.getModifiers())) {
            throw new IllegalStateException("@" + Domain.class.getSimpleName() + " 不能标注在抽象类上："
                    + source.getName());
        }
        if (source.isMemberClass() && !Modifier.isStatic(source.getModifiers())) {
            throw new IllegalStateException("@" + Domain.class.getSimpleName() + " 不能标注在非静态内部类上："
                    + source.getName());
        }
    }

    private static DomainModule toModule(Class<?> source) {
        Domain domain = AnnotationUtils.findAnnotation(source, Domain.class);
        if (domain == null) {
            throw new IllegalStateException("类 " + source.getName() + " 被扫描为领域模块，但未声明 @"
                    + Domain.class.getSimpleName());
        }
        String name = domain.value().trim();
        if (name.isBlank()) {
            throw new IllegalStateException("@" + Domain.class.getSimpleName() + " 在 " + source.getName()
                    + " 上声明的模块名为空白");
        }
        return new DomainModule(name, resolveScanPackages(source, domain), source);
    }

    private static List<String> resolveScanPackages(Class<?> source, Domain domain) {
        String[] declared = domain.scanPackages();
        if (declared.length == 0) {
            return List.of(ClassUtils.getPackageName(source));
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String candidate : declared) {
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalStateException("@" + Domain.class.getSimpleName() + " 在 " + source.getName()
                        + " 上声明的 scanPackages 含空白项");
            }
            normalized.add(candidate.trim());
        }
        return List.copyOf(normalized);
    }
}
