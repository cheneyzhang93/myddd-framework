package org.deep361.myddd.kernel.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link ExtensionRegistry} 默认实现：登记期校验 + 不可变快照。
 *
 * <p>构建流程：{@link Builder#register(Object)} 逐条登记（单条参数校验）→ {@link Builder#build()} 完成跨登记
 * 校验（同场景同优先级冲突）并按优先级排序冻结。注册表构建后不可变，可安全跨线程共享。
 */
public final class DefaultExtensionRegistry implements ExtensionRegistry {

    /** 默认场景标识：非空场景无精确匹配时的兜底登记场景（兜底路径唯一真源，见 {@link #candidates}）。 */
    private static final String DEFAULT_SCENARIO = "";

    /** 每个扩展点 → 场景 → 已按优先级排序的登记项。 */
    private final Map<Class<?>, Map<String, List<Registered>>> byPoint;

    private DefaultExtensionRegistry(Map<Class<?>, Map<String, List<Registered>>> byPoint) {
        this.byPoint = byPoint;
    }

    /** 创建构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <T> T getExtension(Class<T> point, String scenario) {
        List<T> extensions = getExtensionList(point, scenario);
        if (extensions.isEmpty()) {
            throw new NoSuchElementException("未找到扩展点实现：point=" + point.getName() + ", scenario=\""
                    + scenario + "\"，已注册场景=" + registeredScenarios(point));
        }
        return extensions.get(0);
    }

    @Override
    public <T> Optional<T> findExtension(Class<T> point, String scenario) {
        return getExtensionList(point, scenario).stream().findFirst();
    }

    @Override
    public <T> List<T> getExtensionList(Class<T> point, String scenario) {
        requireExtensionPoint(point);
        Objects.requireNonNull(scenario, "scenario 不能为 null，默认场景请传空字符串");
        return candidates(point, scenario).stream()
                .map(extension -> point.cast(extension.instance()))
                .toList();
    }

    @Override
    public <T> List<T> getExtensionList(Class<T> point) {
        requireExtensionPoint(point);
        Map<String, List<Registered>> byScenario = byPoint.get(point);
        if (byScenario == null) {
            return List.of();
        }
        return byScenario.values().stream()
                .flatMap(List::stream)
                .sorted(Registered.ORDER)
                .map(registered -> point.cast(registered.instance()))
                .toList();
    }

    @Override
    public Set<Class<?>> registeredExtensionPoints() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(byPoint.keySet()));
    }

    @Override
    public List<ExtensionMeta> extensionsOf(Class<?> point) {
        requireExtensionPoint(point);
        Map<String, List<Registered>> byScenario = byPoint.get(point);
        if (byScenario == null) {
            return List.of();
        }
        List<ExtensionMeta> metas = new ArrayList<>();
        new TreeSet<>(byScenario.keySet()).forEach(scenario -> byScenario.get(scenario).stream()
                .sorted(Registered.ORDER)
                .forEach(registered -> metas.add(new ExtensionMeta(point, pointName(point), registered.scenario(),
                        registered.priority(), registered.instance().getClass()))));
        return List.copyOf(metas);
    }

    @Override
    public int size() {
        return byPoint.values().stream().mapToInt(byScenario -> byScenario.values().stream()
                .mapToInt(List::size).sum()).sum();
    }

    /**
     * 候选匹配算法（语义固定，勿改）：精确场景优先；精确场景无实现时退化为默认场景（{@code ""}）实现；
     * 请求默认场景时不做兜底。返回列表已按优先级升序（构建期排序 + 同优先级冲突拒绝）。
     */
    private List<Registered> candidates(Class<?> point, String scenario) {
        Map<String, List<Registered>> byScenario = byPoint.get(point);
        if (byScenario == null) {
            return List.of();
        }
        List<Registered> matched = byScenario.get(scenario);
        if (matched == null && !DEFAULT_SCENARIO.equals(scenario)) {
            matched = byScenario.get(DEFAULT_SCENARIO);
        }
        return matched == null ? List.of() : matched;
    }

    private Set<String> registeredScenarios(Class<?> point) {
        Map<String, List<Registered>> byScenario = byPoint.get(point);
        return byScenario == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(byScenario.keySet()));
    }

    private static String pointName(Class<?> point) {
        String name = point.getAnnotation(ExtensionPoint.class).value();
        return name.isBlank() ? point.getSimpleName() : name;
    }

    private static void requireExtensionPoint(Class<?> point) {
        Objects.requireNonNull(point, "point 不能为 null");
        if (!point.isInterface()) {
            throw new IllegalArgumentException(point.getName() + " 不是接口，@ExtensionPoint 只能标注接口");
        }
        if (!point.isAnnotationPresent(ExtensionPoint.class)) {
            throw new IllegalArgumentException(point.getName() + " 缺少 @ExtensionPoint 注解，不能作为扩展点");
        }
    }

    private static Map<Class<?>, Map<String, List<Registered>>> freeze(
            Map<Class<?>, Map<String, List<Registered>>> source) {
        Map<Class<?>, Map<String, List<Registered>>> frozen = new LinkedHashMap<>();
        source.forEach((point, byScenario) -> {
            Map<String, List<Registered>> scenarios = new LinkedHashMap<>();
            byScenario.forEach((scenario, registrations) -> scenarios.put(scenario, List.copyOf(registrations)));
            frozen.put(point, Collections.unmodifiableMap(scenarios));
        });
        return Collections.unmodifiableMap(frozen);
    }

    /** 登记项：场景 + 优先级 + 实例。 */
    private record Registered(String scenario, int priority, Object instance) {

        /** 优先级升序；同优先级按实现类全名升序，保证顺序唯一确定（同场景同优先级已在构建期拒绝）。 */
        static final Comparator<Registered> ORDER = Comparator.comparingInt(Registered::priority)
                .thenComparing(registered -> registered.instance().getClass().getName());
    }

    /** 注册表构建器：登记期做单条校验，{@link #build()} 做跨登记校验并冻结。 */
    public static final class Builder {

        private final Map<Class<?>, List<Registered>> registrations = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * 按实现类上的 {@link Extension} 注解登记。
         *
         * @throws IllegalArgumentException 实现类缺少 {@code @Extension} 注解，或注解声明的扩展点非法
         * @throws IllegalStateException    未显式声明 {@code point} 且无法从实现的接口唯一推断扩展点
         */
        public Builder register(Object extension) {
            Objects.requireNonNull(extension, "扩展实现不能为 null");
            Class<?> implementation = extension.getClass();
            Extension annotation = implementation.getAnnotation(Extension.class);
            if (annotation == null) {
                throw new IllegalArgumentException(implementation.getName() + " 缺少 @Extension 注解");
            }
            Class<?> point = annotation.point() == void.class ? inferPoint(implementation) : annotation.point();
            return register(point, annotation.scenario(), annotation.priority(), extension);
        }

        /**
         * 登记一条扩展实现（不依赖注解，供测试与动态装配使用）。
         *
         * @param point     扩展点接口，须标注 {@link ExtensionPoint}
         * @param scenario  场景标识，不可为 {@code null}，默认场景传空字符串
         * @param priority  优先级，不可为负
         * @param extension 扩展实现实例，必须实现 {@code point}
         */
        public Builder register(Class<?> point, String scenario, int priority, Object extension) {
            Objects.requireNonNull(extension, "扩展实现不能为 null");
            Objects.requireNonNull(scenario, "scenario 不能为 null，默认场景请传空字符串");
            requireExtensionPoint(point);
            if (priority < 0) {
                throw new IllegalArgumentException("priority 不能为负数：" + priority);
            }
            if (!point.isInstance(extension)) {
                throw new IllegalArgumentException(
                        extension.getClass().getName() + " 未实现扩展点 " + point.getName());
            }
            registrations.computeIfAbsent(point, key -> new ArrayList<>())
                    .add(new Registered(scenario, priority, extension));
            return this;
        }

        /**
         * 完成跨登记校验并冻结为不可变注册表。
         *
         * @throws IllegalStateException 同一扩展点同一场景存在同优先级的多份实现（无法确定唯一生效实现）
         */
        public DefaultExtensionRegistry build() {
            Map<Class<?>, Map<String, List<Registered>>> byPoint = new LinkedHashMap<>();
            registrations.forEach((point, registered) -> {
                Map<String, List<Registered>> byScenario = new LinkedHashMap<>();
                for (Registered candidate : registered) {
                    List<Registered> samePriority = byScenario
                            .getOrDefault(candidate.scenario(), List.of()).stream()
                            .filter(existing -> existing.priority() == candidate.priority())
                            .toList();
                    if (!samePriority.isEmpty()) {
                        throw new IllegalStateException("扩展点 " + point.getName() + " 场景 \"" + candidate.scenario()
                                + "\" 存在同优先级(" + candidate.priority() + ")的多个实现："
                                + implNames(samePriority, candidate) + "，请调整 priority 或改用不同场景");
                    }
                    byScenario.computeIfAbsent(candidate.scenario(), key -> new ArrayList<>()).add(candidate);
                }
                byScenario.values().forEach(list -> list.sort(Registered.ORDER));
                byPoint.put(point, byScenario);
            });
            return new DefaultExtensionRegistry(freeze(byPoint));
        }

        private static String implNames(List<Registered> samePriority, Registered candidate) {
            List<String> names = new ArrayList<>();
            samePriority.forEach(existing -> names.add(existing.instance().getClass().getName()));
            names.add(candidate.instance().getClass().getName());
            return String.join(", ", names);
        }

        private static Class<?> inferPoint(Class<?> implementation) {
            List<Class<?>> candidates = new ArrayList<>();
            for (Class<?> type : implementation.getInterfaces()) {
                collectExtensionPoints(type, candidates);
            }
            if (candidates.size() != 1) {
                throw new IllegalStateException(implementation.getName() + " 无法推断扩展点：实现 "
                        + candidates.size() + " 个 @ExtensionPoint 接口，请在 @Extension( point = ... ) 中显式指定");
            }
            return candidates.get(0);
        }

        private static void collectExtensionPoints(Class<?> type, List<Class<?>> found) {
            if (found.contains(type)) {
                return;
            }
            if (type.isAnnotationPresent(ExtensionPoint.class)) {
                found.add(type);
            }
            for (Class<?> superInterface : type.getInterfaces()) {
                collectExtensionPoints(superInterface, found);
            }
        }
    }
}
