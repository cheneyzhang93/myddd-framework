package org.deep361.myddd.kernel.rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 规则包：一组有序规则 + <b>首个匹配者生效</b>的执行语义，不可变、线程安全。
 *
 * <p>规则顺序即业务优先级：{@link #apply(RuleMatch)} 依次求值 {@link Rule#matches(RuleMatch)}，
 * 命中的第一条规则被 {@link Rule#apply(RuleMatch)} 执行并返回；<b>之后的规则既不求值也不执行</b>。
 * 未命中任何规则返回 {@link Optional#empty()}（"无规则适用"是正常业务分支，不是异常）。
 *
 * <p>规则名在包内唯一（构建期校验）：命中日志、灰度开关都依赖"规则名"能唯一定位一条规则。
 */
public final class RulePackage<O> {

    private final String name;
    private final List<Rule<O>> rules;

    private RulePackage(String name, List<Rule<O>> rules) {
        this.name = name;
        this.rules = List.copyOf(rules);
    }

    /** 创建构建器。 */
    public static <O> Builder<O> builder(String name) {
        return new Builder<>(name);
    }

    /** 规则包名（全局唯一标识，注册表按它查找）。 */
    public String name() {
        return name;
    }

    /** 全部规则，按登记顺序。 */
    public List<Rule<O>> rules() {
        return rules;
    }

    /** 规则条数。 */
    public int size() {
        return rules.size();
    }

    /** 全部适用规则，按登记顺序（用于审计/调试"为什么命中这条"）。 */
    public List<Rule<O>> matching(RuleMatch match) {
        Objects.requireNonNull(match, "match 不能为 null");
        return rules.stream().filter(rule -> rule.matches(match)).toList();
    }

    /** 第一条适用规则。 */
    public Optional<Rule<O>> firstMatch(RuleMatch match) {
        Objects.requireNonNull(match, "match 不能为 null");
        return rules.stream().filter(rule -> rule.matches(match)).findFirst();
    }

    /** 执行第一条适用规则并返回其结果；无适用规则返回空。 */
    public Optional<O> apply(RuleMatch match) {
        return firstMatch(match).map(rule -> rule.apply(match));
    }

    /** 规则包构建器。 */
    public static final class Builder<O> {

        private final String name;
        private final List<Rule<O>> rules = new ArrayList<>();
        private final Set<String> ruleNames = new LinkedHashSet<>();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name 不能为 null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name 不能为空白");
            }
        }

        /**
         * 追加一条规则（顺序即优先级）。
         *
         * @throws IllegalArgumentException 规则名为空白
         * @throws IllegalStateException    包内规则名重复
         */
        public Builder<O> rule(Rule<O> rule) {
            Objects.requireNonNull(rule, "rule 不能为 null");
            String ruleName = Objects.requireNonNull(rule.name(), "规则 name() 不能为 null");
            if (ruleName.isBlank()) {
                throw new IllegalArgumentException("规则 name 不能为空白");
            }
            if (!ruleNames.add(ruleName)) {
                throw new IllegalStateException("规则包 " + name + " 内规则名重复：" + ruleName);
            }
            rules.add(rule);
            return this;
        }

        /**
         * 冻结规则包。
         *
         * @throws IllegalStateException 一条规则都没有（空规则包必然是配置遗漏）
         */
        public RulePackage<O> build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException("规则包 " + name + " 必须至少包含一条规则");
            }
            return new RulePackage<>(name, rules);
        }
    }
}
