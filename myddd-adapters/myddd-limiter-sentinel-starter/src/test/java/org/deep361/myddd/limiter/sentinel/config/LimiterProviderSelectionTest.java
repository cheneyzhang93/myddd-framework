package org.deep361.myddd.limiter.sentinel.config;

import java.util.Set;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code myddd.limiter.provider} 的判定（{@code PS} 组）：纯函数，不需要真把另一家 provider 摆上 classpath。 */
class LimiterProviderSelectionTest {

    /** PS1：显式选中本 provider——一份在场还是两份在场，答案相同。 */
    @Test
    void should_assemble_when_sentinel_is_explicitly_selected() {
        assertTrue(LimiterProviderSelection.shouldAssembleSentinel("sentinel", Set.of("sentinel")));
        assertTrue(LimiterProviderSelection.shouldAssembleSentinel("sentinel", Set.of("sentinel", "resilience4j")));
    }

    /** PS2：显式选中另一家——哪怕 classpath 上只有我们这一份也不装配。 */
    @Test
    void should_stand_down_when_another_provider_is_explicitly_selected() {
        assertFalse(LimiterProviderSelection.shouldAssembleSentinel("resilience4j", Set.of("sentinel")));
        assertFalse(LimiterProviderSelection.shouldAssembleSentinel("resilience4j", Set.of("sentinel", "resilience4j")));
    }

    /** PS3：没写这一键且只有一份 provider ⇒ 那一份就是答案（null 与空串两种绑法都要走到同一支）。 */
    @Test
    void should_assemble_by_default_when_only_one_provider_is_on_the_classpath() {
        assertTrue(LimiterProviderSelection.shouldAssembleSentinel(null, Set.of("sentinel")));
        assertTrue(LimiterProviderSelection.shouldAssembleSentinel("", Set.of(LimiterProviderSelection.SENTINEL)));
    }

    /** PS4：没写这一键而两份都在 ⇒ 快速失败，消息必须能让人一眼知道该填什么。 */
    @Test
    void should_fail_fast_when_the_choice_is_ambiguous() {
        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> LimiterProviderSelection.shouldAssembleSentinel(null, Set.of("sentinel", "resilience4j")));
        assertThat(failure.getMessage())
                .contains(LimiterProviderSelection.KEY, "sentinel", "resilience4j")
                .contains("§0.3-6");
    }

    /** PS5：空白值等于"没写"，不是"写了一个没人认识的值"。 */
    @Test
    void should_treat_a_blank_value_as_not_written() {
        assertTrue(LimiterProviderSelection.shouldAssembleSentinel("   ", Set.of("sentinel")));
        assertThrows(FrameworkException.class,
                () -> LimiterProviderSelection.shouldAssembleSentinel("   ", Set.of("sentinel", "resilience4j")));
    }
}
