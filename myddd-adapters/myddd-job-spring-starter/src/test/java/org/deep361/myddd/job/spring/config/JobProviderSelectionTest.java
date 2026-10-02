package org.deep361.myddd.job.spring.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code myddd.job.provider} 的判定（{@code PS} 组）：纯函数，不需要真把另一家 provider 摆上 classpath。 */
class JobProviderSelectionTest {

    /** PS1：显式选中本 provider——一边在场还是两边都在场，答案相同。 */
    @Test
    void should_assemble_when_spring_is_explicitly_selected() {
        assertTrue(JobProviderSelection.shouldAssembleSpring("spring", Set.of("spring")));
        assertTrue(JobProviderSelection.shouldAssembleSpring("spring", Set.of("spring", "xxljob")));
    }

    /** PS2：显式选中另一家——哪怕 classpath 上只有我们这一份也不装配。 */
    @Test
    void should_stand_down_when_another_provider_is_explicitly_selected() {
        assertFalse(JobProviderSelection.shouldAssembleSpring("xxljob", Set.of("spring")));
        assertFalse(JobProviderSelection.shouldAssembleSpring("xxljob", Set.of("spring", "xxljob")));
    }

    /** PS3：没写这一键，且只有一份 provider ⇒ 那一份就是答案。null 与空串两种形态各断一句，"只判 null"的变异当场变红。 */
    @Test
    void should_assemble_by_default_when_only_one_provider_is_on_the_classpath() {
        assertTrue(JobProviderSelection.shouldAssembleSpring(null, Set.of("spring")));
        assertTrue(JobProviderSelection.shouldAssembleSpring("", Set.of(JobProviderSelection.SPRING)));
    }

    /** PS4：没写这一键而两份都在 ⇒ 快速失败，消息必须能让人一眼知道该填什么。 */
    @Test
    void should_fail_fast_when_the_choice_is_ambiguous() {
        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> JobProviderSelection.shouldAssembleSpring(null, Set.of("spring", "xxljob")));
        assertThat(failure.getMessage())
                .contains(JobProviderSelection.KEY, "spring", "xxljob")
                .contains("§0.3-6");
    }

    /** PS5：空白值等于"没写"，不是"写了一个没人认识的值"——两条分支上都要与"未写"等价。 */
    @Test
    void should_treat_a_blank_value_as_not_written() {
        assertTrue(JobProviderSelection.shouldAssembleSpring("   ", Set.of("spring")));
        assertThrows(FrameworkException.class,
                () -> JobProviderSelection.shouldAssembleSpring("   ", Set.of("spring", "xxljob")));
    }
}
