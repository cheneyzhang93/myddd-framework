package org.deep361.myddd.kernel.ddd.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    @Test
    void should_be_usable_as_lambda() {
        IdGenerator generator = () -> 42L;

        assertThat(generator.nextId()).isEqualTo(42L);
    }

    @Test
    void next_id_should_return_primitive_long() throws NoSuchMethodException {
        Method nextId = IdGenerator.class.getMethod("nextId");

        // 基本类型 long：装箱返回 Long 会允许 null id，与全脚手架"id 创建即确定且非空"的契约冲突。
        assertThat(nextId.getReturnType()).isEqualTo(long.class);
    }

    @Test
    void should_declare_exactly_one_abstract_method() {
        // 单抽象方法（SAM）是 lambda / 方法引用可用的前提：新增第二个抽象方法会让所有使用方编译失败。
        long abstractMethodCount = Arrays.stream(IdGenerator.class.getDeclaredMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .count();

        assertThat(abstractMethodCount).isEqualTo(1);
    }

    @Test
    void should_be_marked_as_functional_interface() {
        // @FunctionalInterface 不产生运行期行为，但它把"本端口只承载一个抽象方法"的意图固定在类型上，
        // 与 EntityTest 中 final 修饰符断言同类：钉住的是刻意选择的类型形状，防止重构中被静默抹去。
        assertThat(IdGenerator.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }
}
