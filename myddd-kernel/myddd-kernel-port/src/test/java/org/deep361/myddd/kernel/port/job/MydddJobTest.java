package org.deep361.myddd.kernel.port.job;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class MydddJobTest {

    @MydddJob(name = "daily-reconcile", cron = "0 0 2 * * ?")
    static class ReconcileJob implements JobHandler {
        @Override
        public void execute(JobContext context) {
        }
    }

    @MydddJob(name = "daily-reconcile", cron = "0 0 2 * * ?", description = "每日对账")
    static class DescribedJob implements JobHandler {
        @Override
        public void execute(JobContext context) {
        }
    }

    @Test
    void should_be_runtime_visible_on_types_only() {
        assertThat(MydddJob.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(MydddJob.class.getAnnotation(Target.class).value()).containsExactly(ElementType.TYPE);
    }

    @Test
    void should_require_name_and_cron_and_default_description() throws NoSuchMethodException {
        MydddJob job = ReconcileJob.class.getAnnotation(MydddJob.class);

        assertThat(job.name()).isEqualTo("daily-reconcile");
        assertThat(job.cron()).isEqualTo("0 0 2 * * ?");
        assertThat(job.description()).isEmpty();
        assertThat(MydddJob.class.getDeclaredMethod("name").getDefaultValue()).isNull();
        assertThat(MydddJob.class.getDeclaredMethod("cron").getDefaultValue()).isNull();
    }

    @Test
    void should_expose_description_when_set() {
        assertThat(DescribedJob.class.getAnnotation(MydddJob.class).description()).isEqualTo("每日对账");
    }
}
