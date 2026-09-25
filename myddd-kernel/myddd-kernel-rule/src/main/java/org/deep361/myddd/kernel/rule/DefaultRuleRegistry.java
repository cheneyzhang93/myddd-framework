package org.deep361.myddd.kernel.rule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link RuleRegistry} 默认实现：构建期校验包名唯一，构建后不可变。
 */
public final class DefaultRuleRegistry implements RuleRegistry {

    private final Map<String, RulePackage<?>> packages;

    private DefaultRuleRegistry(Map<String, RulePackage<?>> packages) {
        this.packages = packages;
    }

    /** 创建构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <O> Optional<RulePackage<O>> find(String name) {
        Objects.requireNonNull(name, "规则包名不能为 null");
        return Optional.ofNullable(packageOrNull(name));
    }

    @Override
    public <O> RulePackage<O> get(String name) {
        Objects.requireNonNull(name, "规则包名不能为 null");
        RulePackage<O> rulePackage = packageOrNull(name);
        if (rulePackage == null) {
            throw new NoSuchElementException("未注册的规则包：" + name + "，已注册规则包=" + packages.keySet());
        }
        return rulePackage;
    }

    @Override
    public <O> Optional<O> apply(String packageName, RuleMatch match) {
        Objects.requireNonNull(match, "match 不能为 null");
        return this.<O>find(packageName).flatMap(rulePackage -> rulePackage.apply(match));
    }

    @Override
    public Set<String> packageNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(packages.keySet()));
    }

    @Override
    public int size() {
        return packages.size();
    }

    @SuppressWarnings("unchecked")
    private <O> RulePackage<O> packageOrNull(String name) {
        return (RulePackage<O>) packages.get(name);
    }

    /** 注册表构建器：包名全局唯一。 */
    public static final class Builder {

        private final Map<String, RulePackage<?>> packages = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * 注册规则包。
         *
         * @throws NullPointerException  规则包为 {@code null}
         * @throws IllegalStateException 包名重复（同名包会让"按名查找"失去确定性）
         */
        public Builder register(RulePackage<?> rulePackage) {
            Objects.requireNonNull(rulePackage, "规则包不能为 null");
            RulePackage<?> existing = packages.putIfAbsent(rulePackage.name(), rulePackage);
            if (existing != null) {
                throw new IllegalStateException("规则包名重复：" + rulePackage.name());
            }
            return this;
        }

        /** 冻结注册表。 */
        public DefaultRuleRegistry build() {
            return new DefaultRuleRegistry(Collections.unmodifiableMap(new LinkedHashMap<>(packages)));
        }
    }
}
