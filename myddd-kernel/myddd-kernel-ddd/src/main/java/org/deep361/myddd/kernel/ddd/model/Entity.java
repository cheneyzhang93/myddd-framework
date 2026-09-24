package org.deep361.myddd.kernel.ddd.model;

import java.util.Objects;

/**
 * 实体基类：以"运行时类型 + 标识"判等，与属性值无关；id 在创建时确定、不可变。
 *
 * <p>{@code equals} / {@code hashCode} 为 final，禁止子类改写；判等要求运行时类型相同
 * （{@code getClass()} 强约束），父类与子类实体即使 id 相同也不相等。id 的生成见
 * {@link IdGenerator}（Plan 1 Task 14 落地），本类只持有标识。
 *
 * <p>注意：本类只覆盖判等所需的标识，不参与属性判等——需要值语义的类型请继承
 * {@link AbstractValueObject}。
 *
 * <p>本类刻意不实现 {@code Serializable}：实体是领域对象而非传输载体，序列化需求（缓存、跨进程）应由
 * 具体场景的适配层承担。子类<b>仅</b>声明 {@code implements Serializable} 不能补救——反序列化仍会在
 * 首个不可序列化父类（本类，无无参构造器）处失败（{@code InvalidClassException: no valid constructor}）。
 * 确需 JDK 序列化时，子类须在实现 {@code Serializable} 的同时提供 {@code writeReplace} 返回可序列化代理
 * 、由代理 {@code readResolve} 重建实体；或改用专用 DTO。
 *
 * <p><b>调用方契约</b>：id 必须是生命周期内稳定的值——其 {@code equals} / {@code hashCode} 一旦变化
 * （如传入可变 id 对象），已存入 {@code HashMap} / {@code HashSet} 的实体将无法再被检索到。
 *
 * <p>本类不覆写 {@code toString}：实体属性由子类持有，基类只能看到 id，调试输出请由子类自行实现
 * （建议包含 id 与关键业务字段）。
 */
public abstract class Entity<ID> {

    private final ID id;

    protected Entity(ID id) {
        this.id = Objects.requireNonNull(id, "实体 id 不能为 null");
    }

    /** 实体标识，创建后不可变。 */
    public final ID getId() {
        return id;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return id.equals(((Entity<?>) other).id);
    }

    @Override
    public final int hashCode() {
        return id.hashCode();
    }
}
