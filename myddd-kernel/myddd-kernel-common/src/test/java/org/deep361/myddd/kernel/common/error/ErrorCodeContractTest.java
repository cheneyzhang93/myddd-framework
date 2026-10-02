package org.deep361.myddd.kernel.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ErrorCode#CODE_PATTERN} 的契约钉子（Plan 4 Task 31，R4）。 */
class ErrorCodeContractTest {

    /** EC1 框架内置码全部命中公开 Pattern，且全部占 MYDDD_ 段位。 */
    @Test
    void should_have_every_framework_code_match_the_published_pattern() {
        for (CommonErrorCode code : CommonErrorCode.values()) {
            assertThat(code.code()).matches(ErrorCode.CODE_PATTERN.pattern());
            assertThat(code.code()).startsWith("MYDDD_");
        }
        // 内置码个数变了要显式改这一行：它同时是"新增一个内置码必须过一遍本契约"的钉子
        assertThat(CommonErrorCode.values()).hasSize(15);
    }

    /** EC2 Pattern 只收『全大写域 + 下划线 + 恰好五位数字』。 */
    @Test
    void should_accept_only_the_documented_code_shape() {
        assertThat("ORDER_40001").matches(ErrorCode.CODE_PATTERN.pattern());
        assertThat("MYDDD_42901").matches(ErrorCode.CODE_PATTERN.pattern());
        // 逐个钉住"差一点就放过"的反例：位数不足/超出、小写、缺分隔符、空域、域含数字
        assertThat("ORDER_4000").doesNotMatch(ErrorCode.CODE_PATTERN.pattern());
        assertThat("ORDER_400011").doesNotMatch(ErrorCode.CODE_PATTERN.pattern());
        assertThat("order_40001").doesNotMatch(ErrorCode.CODE_PATTERN.pattern());
        assertThat("ORDER40001").doesNotMatch(ErrorCode.CODE_PATTERN.pattern());
        assertThat("_40001").doesNotMatch(ErrorCode.CODE_PATTERN.pattern());
        assertThat("ORD2_40001").doesNotMatch(ErrorCode.CODE_PATTERN.pattern());
    }

    /** EC3 Pattern 是编译好的常量而非每次新建（同一实例）。 */
    @Test
    void should_expose_the_pattern_as_a_stable_constant() {
        assertThat(ErrorCode.CODE_PATTERN).isSameAs(ErrorCode.CODE_PATTERN);
        assertThat(ErrorCode.CODE_PATTERN.pattern()).isEqualTo("[A-Z]+_\\d{5}");
    }
}
