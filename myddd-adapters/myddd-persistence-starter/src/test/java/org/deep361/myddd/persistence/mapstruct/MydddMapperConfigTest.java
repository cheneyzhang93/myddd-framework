package org.deep361.myddd.persistence.mapstruct;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基座策略里运行期可见的三条，各有一条可证伪的断言：注解在不在、构造器长什么样、null 怎么办。
 *
 * <p>断言对象是 {@code Probe*MapperImpl}——MapStruct 在编译期按 {@link MydddMapperConfig} 生成的实现。
 * 也就是说这些用例测的是"基座确实改变了生成物"，而不是"基座上有几个注解"：把任何一条策略改回
 * MapStruct 默认值，对应的那条用例立刻红（见 Step 10 的变异清单）。
 *
 * <p>另外两条策略（{@code unmappedTargetPolicy}、{@code suppressTimestampInGenerated}）在字节码里
 * 没有可断言的痕迹，只能作为编译期/构建期证据钉住（Step 6 与 Step 5 的 grep）。
 */
class MydddMapperConfigTest {

    @Test
    void should_copy_field_by_field_and_allow_explicit_ignores() {
        ProbeSrc src = new ProbeSrc();
        src.setName("订单-001");
        src.setAt(Instant.parse("2026-09-26T02:00:00Z"));

        ProbeDst dst = new ProbeMapperImpl().toDst(src);

        assertThat(dst.getName()).isEqualTo("订单-001");
        assertThat(dst.getAt()).isEqualTo(Instant.parse("2026-09-26T02:00:00Z"));
        assertThat(dst.getSecret()).isNull();
    }

    @Test
    void should_be_adoptable_as_spring_component() {
        assertThat(ProbeMapperImpl.class.getAnnotation(Component.class)).isNotNull();
    }

    @Test
    void should_inject_collaborators_through_the_constructor() {
        Constructor<?>[] constructors = ProbeDependentMapperImpl.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(ProbeCollaborator.class);

        Field[] fields = ProbeDependentMapperImpl.class.getDeclaredFields();
        assertThat(fields).hasSize(1);
        // 字段注入的形态是"非 final + 带 @Autowired"；这里要求协作者字段既 final 又不带任何注解
        assertThat(Arrays.stream(fields).allMatch(f -> Modifier.isFinal(f.getModifiers()))).isTrue();
        assertThat(Arrays.stream(fields).allMatch(f -> f.getAnnotations().length == 0)).isTrue();
    }

    @Test
    void should_not_null_out_fields_missing_from_source() {
        ProbeSrc src = new ProbeSrc();
        src.setAt(Instant.parse("2026-09-26T03:00:00Z"));
        ProbeDst dst = new ProbeDst();
        dst.setName("已有值");
        dst.setAt(Instant.parse("2026-09-26T02:00:00Z"));

        new ProbeUpdateMapperImpl().update(src, dst);

        assertThat(dst.getName()).isEqualTo("已有值");
        assertThat(dst.getAt()).isEqualTo(Instant.parse("2026-09-26T03:00:00Z"));
    }
}
