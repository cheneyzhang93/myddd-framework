package org.deep361.myddd.domain.context;

import org.deep361.myddd.kernel.ddd.context.Domain;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

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
 * <p>扫描结果按模块名升序 —— 装配结果必须确定，否则"哪个模块先装配"会随 classpath 顺序漂移。
 *
 * <p><b>失败即启动失败</b>：基础包为空/空白、模块名为空白、{@code @Domain.scanPackages} 含空白项，一律抛异常。
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
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Domain.class));

        Set<String> seen = new LinkedHashSet<>();
        List<DomainModule> modules = new ArrayList<>();
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : provider.findCandidateComponents(basePackage)) {
                String className = candidate.getBeanClassName();
                if (className == null || !seen.add(className)) {
                    continue;
                }
                modules.add(toModule(className));
            }
        }
        modules.sort(Comparator.comparing(DomainModule::name));
        return List.copyOf(modules);
    }

    private static DomainModule toModule(String className) {
        Class<?> source = ClassUtils.resolveClassName(className, ClassUtils.getDefaultClassLoader());
        Domain domain = AnnotationUtils.findAnnotation(source, Domain.class);
        if (domain == null) {
            throw new IllegalStateException("类 " + className + " 被扫描为领域模块，但未声明 @"
                    + Domain.class.getSimpleName());
        }
        String name = domain.value().trim();
        if (name.isEmpty()) {
            throw new IllegalStateException("@" + Domain.class.getSimpleName() + " 在 " + className
                    + " 上声明的模块名为空白");
        }
        return new DomainModule(name, resolveScanPackages(className, domain), source);
    }

    private static List<String> resolveScanPackages(String className, Domain domain) {
        String[] declared = domain.scanPackages();
        if (declared.length == 0) {
            return List.of(ClassUtils.getPackageName(className));
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String candidate : declared) {
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalStateException("@" + Domain.class.getSimpleName() + " 在 " + className
                        + " 上声明的 scanPackages 含空白项");
            }
            normalized.add(candidate.trim());
        }
        return List.copyOf(normalized);
    }
}
