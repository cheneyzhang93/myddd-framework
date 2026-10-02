package org.deep361.myddd.web.autoconfigure;

import org.deep361.myddd.web.trace.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 装配的三层取舍：总开关、子开关、业务自定义优先。
 */
class MydddWebTraceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddWebAutoConfiguration.class,
                    MydddWebTraceAutoConfiguration.class));

    @Test
    void should_register_trace_filter_by_default() {
        this.runner.run(context -> assertThat(context).hasSingleBean(TraceIdFilter.class));
    }

    @Test
    void should_not_register_filter_when_master_switch_off() {
        this.runner.withPropertyValues("myddd.web.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TraceIdFilter.class));
    }

    @Test
    void should_not_register_filter_when_trace_switch_off_but_keep_others() {
        this.runner.withPropertyValues("myddd.web.trace.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TraceIdFilter.class)
                        .hasSingleBean(MydddWebProperties.class));
    }

    @Test
    void should_back_off_when_application_declares_its_own_trace_filter() {
        this.runner.withBean(TraceIdFilter.class, TraceIdFilter::new)
                .run(context -> assertThat(context).hasSingleBean(TraceIdFilter.class));
    }
}
