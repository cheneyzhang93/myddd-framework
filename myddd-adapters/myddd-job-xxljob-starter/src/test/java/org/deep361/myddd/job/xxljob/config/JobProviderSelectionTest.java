package org.deep361.myddd.job.xxljob.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code myddd.job.provider} 在 XXL-Job 侧的判定（{@code PS} 组，五条纯函数、不起容器）。 */
class JobProviderSelectionTest {

    private static final Set<String> BOTH = Set.of("spring", "xxljob");

    /** PS1：显式选中本 provider——一边在场还是两边都在场，答案相同。 */
    @Test
    void should_assemble_when_explicitly_selected_regardless_of_classpath() {
        assertTrue(JobProviderSelection.shouldAssembleXxljob("xxljob", Set.of("xxljob")));
        assertTrue(JobProviderSelection.shouldAssembleXxljob("xxljob", BOTH));
    }

    /** PS2：显式选中另一家——哪怕 classpath 上只有我们这一份也不装配。 */
    @Test
    void should_stand_down_when_the_other_provider_is_selected() {
        assertFalse(JobProviderSelection.shouldAssembleXxljob("spring", Set.of("xxljob")));
        assertFalse(JobProviderSelection.shouldAssembleXxljob("spring", BOTH));
    }

    /** PS3：没写这一键，且只有一份 provider ⇒ 那一份就是答案（null 与空串两种"没写"形态都要走到这一支）。 */
    @Test
    void should_assemble_by_default_when_only_one_provider_is_on_the_classpath() {
        assertTrue(JobProviderSelection.shouldAssembleXxljob(null, Set.of("xxljob")));
        assertTrue(JobProviderSelection.shouldAssembleXxljob("", Set.of("xxljob")));
    }

    /** PS4：没写这一键而两份都在 ⇒ 快速失败，消息必须能让人一眼知道该填什么。 */
    @Test
    void should_fail_fast_when_the_provider_is_ambiguous() {
        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> JobProviderSelection.shouldAssembleXxljob(null, BOTH));
        assertThat(failure.getMessage()).contains(JobProviderSelection.KEY, "spring", "xxljob", "§0.3-6");
    }

    /** PS5：空白值等于"没写"，不是"写了一个没人认识的值"。 */
    @Test
    void should_treat_a_blank_value_as_not_written() {
        assertTrue(JobProviderSelection.shouldAssembleXxljob("   ", Set.of("xxljob")));
        assertThrows(FrameworkException.class, () -> JobProviderSelection.shouldAssembleXxljob("   ", BOTH));
    }
}
