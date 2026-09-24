package org.deep361.myddd.kernel.ddd.model;

import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 值对象基类：基于<b>非静态、非瞬态、非合成</b>字段的反射判等与哈希，字段清单按类缓存。
 *
 * <p>缓存使用 {@link ClassValue} 而非静态 Map：它随 Class 卸载自动回收，不会在热部署/多类加载器
 * 场景下把 Class 钉在静态容器里造成类加载器泄漏。
 *
 * <p>{@code equals} / {@code hashCode} 为 final，禁止子类改写；判等要求运行时类型相同
 * （{@code getClass()} 强约束），父类与子类实例即使字段值相同也不相等。自定义判等语义的值对象
 * 请勿继承本类。
 *
 * <p><b>调用方契约</b>：①子类字段应为不可变值——字段一经修改，equals/hashCode 结果随之变化，
 * 已存入 {@code HashSet} / {@code HashMap} 的实例将无法再被检索到；②数组字段按引用判等
 * （{@code Objects.equals} 语义），需要按内容比较时请改用 {@code List} 等值语义容器；③JPMS 具名
 * 模块中的子类需将所在包 {@code opens}（classpath 模式无此约束），否则字段反射读取抛
 * {@link java.lang.reflect.InaccessibleObjectException}。
 */
public abstract class AbstractValueObject implements ValueObject, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = type; current != AbstractValueObject.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                        continue;
                    }
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            return List.copyOf(fields);
        }
    };

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        for (Field field : FIELDS.get(getClass())) {
            if (!Objects.equals(read(field, this), read(field, other))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public final int hashCode() {
        int result = 1;
        for (Field field : FIELDS.get(getClass())) {
            result = 31 * result + Objects.hashCode(read(field, this));
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(getClass().getSimpleName()).append('[');
        List<Field> fields = FIELDS.get(getClass());
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(fields.get(index).getName()).append('=').append(read(fields.get(index), this));
        }
        return builder.append(']').toString();
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法读取值对象字段: " + field, exception);
        }
    }
}
