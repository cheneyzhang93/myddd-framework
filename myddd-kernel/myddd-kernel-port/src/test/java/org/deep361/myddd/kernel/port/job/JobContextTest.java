package org.deep361.myddd.kernel.port.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobContextTest {

    @Test
    void should_carry_job_name_and_param() {
        JobContext context = new JobContext("daily-reconcile", "2026-09-25");

        assertThat(context.jobName()).isEqualTo("daily-reconcile");
        assertThat(context.param()).isEqualTo("2026-09-25");
    }

    @Test
    void of_should_default_param_to_empty() {
        assertThat(JobContext.of("daily-reconcile").param()).isEmpty();
    }

    @Test
    void should_normalize_null_param_to_empty() {
        assertThat(new JobContext("daily-reconcile", null).param()).isEmpty();
    }

    @Test
    void should_reject_invalid_job_name() {
        assertThatThrownBy(() -> JobContext.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("jobName 不能为 null");
        assertThatThrownBy(() -> JobContext.of(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jobName 不能为空白");
    }

    @Test
    void should_implement_value_equality() {
        assertThat(JobContext.of("j")).isEqualTo(JobContext.of("j"));
        assertThat(JobContext.of("j")).isNotEqualTo(new JobContext("j", "p"));
    }
}
