package org.deep361.myddd.kernel.rule;

import java.util.Optional;
import java.util.Set;

/**
 * 规则注册表：按规则包名查找并执行规则包。
 *
 * <p><b>泛型约束</b>：{@code <O> RulePackage<O> get(String)} 的类型参数由<b>调用方声明</b>，运行期不做校验——
 * 取错出参类型会在 {@code apply} 的结果处抛 {@link ClassCastException}。规则包名与出参类型的对应关系属业务约定，
 * 建议在常量类中集中声明包名。
 *
 * <p>实现不可变、线程安全。
 */
public interface RuleRegistry {

    /** 查找规则包。 */
    <O> Optional<RulePackage<O>> find(String name);

    /**
     * 取规则包。
     *
     * @throws java.util.NoSuchElementException 包未注册（消息含已注册包名）
     */
    <O> RulePackage<O> get(String name);

    /** 在指定规则包上执行匹配；包未注册返回空（与"包存在但无规则适用"同语义，均为空结果）。 */
    <O> Optional<O> apply(String packageName, RuleMatch match);

    /** 已注册的规则包名，按登记顺序。 */
    Set<String> packageNames();

    /** 已注册规则包数量。 */
    int size();
}
