package org.deep361.myddd.kernel.rule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * 规则事实表：规则匹配与执行时可见的全部输入，不可变。
 *
 * <p>以 {@code Map<String,Object>} 承载事实（如 {@code level=VIP}、{@code amount=100.00}），是为了让规则可配置化
 * 而不必为每种条件新增类型；代价是属性为弱类型——因此读取必须走 {@link #requireAttribute(String, Class)}，
 * 缺失或类型不符立即抛错，绝不静默返回 {@code null}（静默的 null 会把"配置写错"变成"规则不生效"这类难查的线上问题）。
 *
 * <p>构造方式：{@link #of(String)} 建立空表，{@link #with(String, Object)} 链式追加；同名属性后写覆盖先写。
 */
public final class RuleMatch {

    private final String scenario;
    private final Map<String, Object> attributes;

    private RuleMatch(String scenario, Map<String, Object> attributes) {
        this.scenario = scenario;
        this.attributes = attributes;
    }

    /**
     * 建立空事实表。
     *
     * @param scenario 事实所属场景（如 {@code "discount"}），不可为空白
     */
    public static RuleMatch of(String scenario) {
        Objects.requireNonNull(scenario, "scenario 不能为 null");
        if (scenario.isBlank()) {
            throw new IllegalArgumentException("scenario 不能为空白");
        }
        return new RuleMatch(scenario, Collections.emptyMap());
    }

    /**
     * 追加/覆盖一个事实，返回新实例（本类不可变）。
     *
     * @param key   属性名，不可为 null
     * @param value 属性值，不可为 null
     */
    public RuleMatch with(String key, Object value) {
        Objects.requireNonNull(key, "属性名不能为 null");
        Objects.requireNonNull(value, "属性值不能为 null：" + key);
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new RuleMatch(scenario, Collections.unmodifiableMap(merged));
    }

    /** 事实所属场景。 */
    public String scenario() {
        return scenario;
    }

    /** 只读事实表，按加入顺序。 */
    public Map<String, Object> attributes() {
        return attributes;
    }

    /** 属性是否存在。 */
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    /** 读取属性，缺失返回空。 */
    public Optional<Object> attribute(String key) {
        Objects.requireNonNull(key, "属性名不能为 null");
        return Optional.ofNullable(attributes.get(key));
    }

    /**
     * 按类型读取属性。
     *
     * @throws NoSuchElementException   属性缺失（消息含可用属性名，便于定位配置错误）
     * @throws IllegalArgumentException 属性类型不符
     */
    public <V> V requireAttribute(String key, Class<V> type) {
        Objects.requireNonNull(key, "属性名不能为 null");
        Objects.requireNonNull(type, "type 不能为 null");
        Object value = attributes.get(key);
        if (value == null) {
            throw new NoSuchElementException("规则事实缺少属性：" + key + "，可用属性=" + attributes.keySet());
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("规则事实属性类型不符：" + key + " 期望 " + type.getSimpleName()
                    + "，实际 " + value.getClass().getName());
        }
        return type.cast(value);
    }

    /** 事实数量。 */
    public int size() {
        return attributes.size();
    }
}
