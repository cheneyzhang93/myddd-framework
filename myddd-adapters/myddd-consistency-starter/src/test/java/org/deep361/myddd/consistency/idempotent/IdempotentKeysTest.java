package org.deep361.myddd.consistency.idempotent;

import java.lang.reflect.Method;
import java.util.List;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2 缺省幂等键的取证（设计文档 §4 裁定 2）：{@code 简单类名.方法名:hex(sha256(规范化串))前16位}。
 *
 * <p>断言分两层是有意的：{@link IdempotentKeys#normalize(Object[])} 是可直接钉的纯函数，而摘要是不透明的
 * 16 个字符——只从最终键断言的话，"数组没展开"与"分隔符换了"会一起表现为"两条不相干的用例同时红"，
 * 说不出坏在哪一段。形状（前缀、长度、字符集）仍从最终键断言，因为那是键的对外契约。
 */
class IdempotentKeysTest {

    /** 被测方法的宿主：形参名要靠父 POM 的 {@code -parameters} 才能读出来，这里不依赖形参名。 */
    static class OrderFacade {

        String submit(String orderNo) {
            return orderNo;
        }

        String createBlank() {
            return "";
        }

        String twoArgs(String tenant, Integer count) {
            return tenant + count;
        }

        String arrayLike(int[] quantities) {
            return String.valueOf(quantities);
        }
    }

    /** 一个"没有重写 toString()"的业务 DTO：R2 的"不含 JVM 身份 hash"就是替它把关。 */
    static class BareDto {
    }

    /** {@code toString()} 写在父类、子类只继承——判据必须认这种情况。 */
    static class InheritsToString {

        @Override
        public String toString() {
            return "inherited";
        }
    }

    static class SubclassOfInherited extends InheritsToString {
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return OrderFacade.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException("夹具里的方法签名变了：" + name, failure);
        }
    }

    @Test
    void should_shape_the_default_key_as_simple_class_dot_method_colon_digest() {
        String key = IdempotentKeys.defaultKey(method("submit", String.class), new Object[] {"o-1"});

        assertThat(key).matches("^OrderFacade\\.submit:[0-9a-f]{16}$");
    }

    @Test
    void should_produce_the_same_default_key_for_calls_that_carry_equal_arguments() {
        Method submit = method("submit", String.class);

        assertThat(IdempotentKeys.defaultKey(submit, new Object[] {"o-1"}))
                .isEqualTo(IdempotentKeys.defaultKey(submit, new Object[] {"o-1".concat("")}));
    }

    @Test
    void should_produce_different_default_keys_for_different_arguments() {
        Method submit = method("submit", String.class);

        assertThat(IdempotentKeys.defaultKey(submit, new Object[] {"o-1"}))
                .isNotEqualTo(IdempotentKeys.defaultKey(submit, new Object[] {"o-2"}));
    }

    @Test
    void should_digest_an_empty_argument_list_into_a_stable_key() {
        Method create = method("createBlank");

        assertThat(IdempotentKeys.normalize(new Object[0])).isEmpty();
        assertThat(IdempotentKeys.defaultKey(create, new Object[0]))
                .isEqualTo(IdempotentKeys.defaultKey(create, new Object[0]));
    }

    @Test
    void should_join_arguments_with_the_pipe_separator_in_declaration_order() {
        Method twoArgs = method("twoArgs", String.class, Integer.class);

        // 声明顺序就是拼接顺序：反过来拼会让同一次调用的键随重载解析而变。
        assertThat(IdempotentKeys.normalize(new Object[] {"t1", 2})).isEqualTo("t1|2");
        assertThat(IdempotentKeys.normalize(new Object[] {2, "t1"})).isEqualTo("2|t1");
        assertThat(IdempotentKeys.defaultKey(twoArgs, new Object[] {"t1", 2}))
                .isNotEqualTo(IdempotentKeys.defaultKey(twoArgs, new Object[] {2, "t1"}));
    }

    @Test
    void should_expand_array_arguments_instead_of_rendering_their_identity_hash() {
        // String.valueOf(int[]) 给出的正是 R2 禁止的那个形态（[I@1b6d3586），所以数组必须自己展开。
        assertThat(IdempotentKeys.normalize(new Object[] {new int[] {1, 2, 3}})).isEqualTo("[1,2,3]");
        assertThat(IdempotentKeys.normalize(new Object[] {new String[] {"a", "b"}})).isEqualTo("[a,b]");
    }

    @Test
    void should_expand_multidimensional_arrays_and_null_elements() {
        String[][] matrix = {{"a", null}, {"b"}};

        assertThat(IdempotentKeys.normalize(new Object[] {matrix})).isEqualTo("[[a,null],[b]]");
    }

    @Test
    void should_render_a_null_argument_as_the_word_null_and_stay_stable() {
        // null 是一个可渲染的值，不是错误：缺省键不能因为实参为 null 就抛 NPE。
        assertThat(IdempotentKeys.normalize(new Object[] {null})).isEqualTo("null");
        assertThat(IdempotentKeys.normalize(new Object[] {null, null})).isEqualTo("null|null");
    }

    @Test
    void should_ignore_argument_types_when_digesting() {
        // R2 明写"不含类型名"，后果就是 Integer 1 与 String "1" 共用同一把键。这条不是遗漏，
        // 是把裁定的代价钉在明面上：接受这个合并请按业务写 key 表达式。
        assertThat(IdempotentKeys.normalize(new Object[] {"1"})).isEqualTo(IdempotentKeys.normalize(new Object[] {1}));
    }

    @Test
    void should_treat_a_single_argument_containing_the_separator_like_two_arguments() {
        // 同一条裁定的第二个后果：分隔符不转义，串里的 | 会移动参数边界。
        assertThat(IdempotentKeys.normalize(new Object[] {"a|b"})).isEqualTo(IdempotentKeys.normalize(new Object[] {"a", "b"}));
    }

    @Test
    void should_fail_fast_when_an_argument_has_no_stable_toString() {
        assertThatThrownBy(() -> IdempotentKeys.normalize(new Object[] {new BareDto(), "o-1"}))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("第 1 个参数")
                .hasMessageContaining(BareDto.class.getName())
                .hasMessageContaining("toString()")
                .hasMessageContaining("key = ");
    }

    @Test
    void should_fail_fast_for_an_array_element_without_a_stable_toString() {
        // 展开数组以后元素也走同一道关，否则身份 hash 会从数组缝里溜进键里。
        assertThatThrownBy(() -> IdempotentKeys.normalize(new Object[] {new BareDto[] {new BareDto()}, 7}))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("第 1 个参数")
                .hasMessageContaining("toString()");
    }

    @Test
    void should_not_reject_a_class_that_overrides_toString_in_a_superclass() {
        // 判据是"解析到的 toString 是不是 Object 那一个"，而不是"是不是自己声明的"：
        // 只继承了一个可用 toString() 的类不该被拒。
        assertThat(IdempotentKeys.normalize(new Object[] {new SubclassOfInherited()})).isEqualTo("inherited");
    }

    @Test
    void should_accept_collections_and_common_values_whose_own_toString_is_stable() {
        assertThat(IdempotentKeys.normalize(new Object[] {List.of("a", 1), true, 3.5, 'x'}))
                .isEqualTo("[a, 1]|true|3.5|x");
    }

    @Test
    void should_keep_the_digest_short_enough_to_stay_a_redis_key_and_a_db_column_value() {
        // 键长不随实参增长是"摘要而不是拼接"的存在理由：一个 DTO 的 toString 可以几百字节。
        String key = IdempotentKeys.defaultKey(method("arrayLike", int[].class),
                new Object[] {new int[4096]});

        assertThat(key).hasSize("OrderFacade.arrayLike:".length() + 16);
    }

    @Test
    void should_digest_the_normalized_string_to_a_golden_value_that_both_stores_can_share() {
        // 期望值是独立算出来的事实值（sha256 的 UTF-8 摘要取前 16 个十六进制字符），不是从实现里抄的。
        // Task 27 要求 Redis 侧与 DB 侧对同一次调用产出同一个键，而那条等价用例需要 Docker；这里用金标准
        // 把摘要函数本身钉住，于是"截取窗口挪一位""换了算法""按平台默认字符集取字节"在无 Redis 的机器上照样红。
        assertThat(IdempotentKeys.defaultKey(method("submit", String.class), new Object[] {"o-1"}))
                .isEqualTo("OrderFacade.submit:5cbdcb742069a582");
        assertThat(IdempotentKeys.defaultKey(method("twoArgs", String.class, Integer.class),
                new Object[] {"a", 1}))
                        .isEqualTo("OrderFacade.twoArgs:df4504ce92500fe8");
        assertThat(IdempotentKeys.defaultKey(method("submit", String.class), new Object[] {"订"}))
                .isEqualTo("OrderFacade.submit:f41b52dd043ab21e");
        assertThat(IdempotentKeys.defaultKey(method("createBlank"), new Object[0]))
                .isEqualTo("OrderFacade.createBlank:e3b0c44298fc1c14");
    }
}
