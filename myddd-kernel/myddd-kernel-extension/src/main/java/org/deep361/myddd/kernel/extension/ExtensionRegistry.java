package org.deep361.myddd.kernel.extension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 扩展注册表：按扩展点 + 场景查找实现。
 *
 * <p><b>场景匹配语义</b>：精确匹配请求的场景；请求非空场景且无精确匹配时，退化为默认场景（{@code ""}）的实现；
 * 两者皆无则视为未找到。
 *
 * <p><b>顺序语义</b>：同一场景内的实现按 {@link Extension#priority()} 升序排列（数值越小越优先），
 * 同优先级属构建期冲突（注册表无法构建），因此排序结果始终唯一确定。
 *
 * <p><b>失败语义</b>：{@link #getExtension(Class, String)} 未找到时抛 {@link java.util.NoSuchElementException}，
 * 消息中包含已注册场景，便于定位是"场景字符串写错"还是"实现没被登记"；不抛异常的探测走
 * {@link #findExtension(Class, String)} 与 {@link #getExtensionList(Class, String)}。
 *
 * <p>实现不可变、线程安全。
 */
public interface ExtensionRegistry {

    /**
     * 取该扩展点在该场景下的唯一实现（优先级最高者）。
     *
     * @param point    扩展点接口，须标注 {@link ExtensionPoint}，不可为 {@code null}
     * @param scenario 场景标识，{@code null} 视为非法（默认场景请传空字符串）
     * @throws java.util.NoSuchElementException 无精确匹配且无默认实现
     */
    <T> T getExtension(Class<T> point, String scenario);

    /** 取该扩展点在该场景下的实现，未找到返回 {@link Optional#empty()}。 */
    <T> Optional<T> findExtension(Class<T> point, String scenario);

    /** 取该扩展点在该场景下的全部实现，按优先级升序；无可匹配实现返回空列表（不抛异常）。 */
    <T> List<T> getExtensionList(Class<T> point, String scenario);

    /** 取该扩展点已登记的全部实现（不分场景），按优先级升序（同优先级按实现类全名升序）。 */
    <T> List<T> getExtensionList(Class<T> point);

    /** 已登记的扩展点集合，按首次登记顺序。 */
    Set<Class<?>> registeredExtensionPoints();

    /** 指定扩展点的登记明细，按场景（字符串升序）→ 优先级升序排列；未登记返回空列表。 */
    List<ExtensionMeta> extensionsOf(Class<?> point);

    /** 已登记的扩展实现总数。 */
    int size();
}
