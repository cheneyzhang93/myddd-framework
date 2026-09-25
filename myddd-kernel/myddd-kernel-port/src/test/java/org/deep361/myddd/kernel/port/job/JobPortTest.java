package org.deep361.myddd.kernel.port.job;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobPortTest {

    static final class RecordingJobPort implements JobPort {
        final Map<String, JobHandler> handlers = new LinkedHashMap<>();

        @Override
        public void register(JobDefinition definition, JobHandler handler) {
            if (handlers.putIfAbsent(definition.name(), handler) != null) {
                throw new IllegalArgumentException("任务重复注册: " + definition.name());
            }
        }

        @Override
        public Set<String> registeredJobs() {
            return Set.copyOf(handlers.keySet());
        }
    }

    @Test
    void should_register_and_list_jobs() {
        RecordingJobPort port = new RecordingJobPort();

        port.register(JobDefinition.of("daily-reconcile", "0 0 2 * * ?"), context -> {
        });

        assertThat(port.registeredJobs()).containsExactly("daily-reconcile");
    }

    @Test
    void should_reject_duplicate_job_name() {
        RecordingJobPort port = new RecordingJobPort();
        port.register(JobDefinition.of("j", "0 0 2 * * ?"), context -> {
        });

        assertThatThrownBy(() -> port.register(JobDefinition.of("j", "0 0 3 * * ?"), context -> {
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务重复注册");
    }

    @Test
    void handler_is_a_functional_interface() {
        JobHandler handler = context -> context.param();

        assertThat(handler).isNotNull();
        assertThat(JobHandler.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }
}
