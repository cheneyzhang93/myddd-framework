package org.deep361.myddd.kernel.port.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobDefinitionTest {

    @Test
    void should_carry_name_cron_and_description() {
        JobDefinition definition = new JobDefinition("daily-reconcile", "0 0 2 * * ?", "每日对账");

        assertThat(definition.name()).isEqualTo("daily-reconcile");
        assertThat(definition.cron()).isEqualTo("0 0 2 * * ?");
        assertThat(definition.description()).isEqualTo("每日对账");
    }

    @Test
    void of_should_default_description_to_empty() {
        assertThat(JobDefinition.of("j", "0 0 2 * * ?").description()).isEmpty();
    }

    @Test
    void should_normalize_null_description_to_empty() {
        assertThat(new JobDefinition("j", "0 0 2 * * ?", null).description()).isEmpty();
    }

    @Test
    void should_reject_blank_name_or_cron() {
        assertThatThrownBy(() -> JobDefinition.of(null, "0 0 2 * * ?"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> JobDefinition.of(" ", "0 0 2 * * ?"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> JobDefinition.of("j", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cron");
        assertThatThrownBy(() -> JobDefinition.of("j", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cron");
    }
}
