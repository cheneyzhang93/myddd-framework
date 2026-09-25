package org.deep361.myddd.domain.context;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 领域模块注册表：应用启动时冻结的模块集合，按模块名索引。
 *
 * <p>不可变：{@link #of(Collection)} 构造后不再变化；模块名重复属配置错误，构造即失败。
 */
public final class DomainModuleRegistry {

    private final Map<String, DomainModule> modules;

    private DomainModuleRegistry(Map<String, DomainModule> modules) {
        this.modules = modules;
    }

    /**
     * 构建注册表（保持传入顺序）。
     *
     * @throws NullPointerException  集合或元素为 {@code null}
     * @throws IllegalStateException 模块名重复
     */
    public static DomainModuleRegistry of(Collection<DomainModule> modules) {
        Objects.requireNonNull(modules, "领域模块集合不能为 null");
        Map<String, DomainModule> byName = new LinkedHashMap<>();
        for (DomainModule module : modules) {
            Objects.requireNonNull(module, "领域模块不能为 null");
            DomainModule existing = byName.putIfAbsent(module.name(), module);
            if (existing != null) {
                throw new IllegalStateException("领域模块名重复：" + module.name());
            }
        }
        return new DomainModuleRegistry(Collections.unmodifiableMap(byName));
    }

    /** 全部模块（注册顺序的不可变快照）。 */
    public List<DomainModule> modules() {
        return List.copyOf(modules.values());
    }

    /** 全部模块名（注册顺序）。 */
    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(modules.keySet()));
    }

    /**
     * 按名取模块。
     *
     * @throws NoSuchElementException 未注册的模块名（消息含已注册模块名，便于排查拼写错误）
     */
    public DomainModule get(String name) {
        Objects.requireNonNull(name, "模块名不能为 null");
        DomainModule module = modules.get(name);
        if (module == null) {
            throw new NoSuchElementException("未注册的领域模块：" + name + "，已注册=" + modules.keySet());
        }
        return module;
    }

    /** 按名取模块，未注册时返回空。 */
    public Optional<DomainModule> find(String name) {
        Objects.requireNonNull(name, "模块名不能为 null");
        return Optional.ofNullable(modules.get(name));
    }

    /** 类型所属的领域模块（按注册顺序取第一个覆盖其包的模块），无归属时返回空。 */
    public Optional<DomainModule> moduleOf(Class<?> type) {
        Objects.requireNonNull(type, "类型不能为 null");
        return modules.values().stream().filter(module -> module.covers(type)).findFirst();
    }

    public int size() {
        return modules.size();
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }
}
