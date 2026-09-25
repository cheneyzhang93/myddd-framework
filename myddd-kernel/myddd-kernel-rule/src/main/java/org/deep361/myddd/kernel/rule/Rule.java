package org.deep361.myddd.kernel.rule;

/**
 * 规则：以事实表判断是否适用（{@link #matches(RuleMatch)}），适用时产出结果（{@link #apply(RuleMatch)}）。
 *
 * <p>无泛型入参、只有泛型出参：入参统一为事实表 {@link RuleMatch}，这让"可配置规则包"（同一模板适用不同业务对象）
 * 成为可能；出参类型由规则包声明，取用方按包名约定类型。
 *
 * @param <O> 规则产出结果类型（如折扣金额 {@code BigDecimal}、布尔判定 {@code Boolean}）
 */
public interface Rule<O> {

    /** 规则名：规则包内唯一，用于日志、命中记录与冲突校验。 */
    String name();

    /** 是否适用。必须无副作用——{@code apply} 只在它返回 {@code true} 时被调用。 */
    boolean matches(RuleMatch match);

    /**
     * 产出结果；仅在 {@link #matches(RuleMatch)} 为 {@code true} 时被调用。
     *
     * <p>不得返回 {@code null}：命中却无结果应在 {@code matches} 中判否；返回 {@code null} 会在
     * 执行时抛 {@link IllegalStateException}（避免把"规则配置错误"静默变成"规则不生效"）。
     */
    O apply(RuleMatch match);
}
