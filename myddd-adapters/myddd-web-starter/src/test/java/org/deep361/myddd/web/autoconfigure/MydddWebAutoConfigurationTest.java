package org.deep361.myddd.web.autoconfigure;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 总开关与启动期校验在真实上下文刷新里的行为。
 */
class MydddWebAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddWebAutoConfiguration.class));

    @Test
    void should_assemble_without_any_property_when_starter_present() {
        this.runner.run(context -> assertThat(context).hasSingleBean(MydddWebProperties.class)
                .hasNotFailed());
    }

    @Test
    void should_assemble_when_master_switch_explicitly_enabled() {
        this.runner.withPropertyValues("myddd.web.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MydddWebProperties.class));
    }

    @Test
    void should_skip_every_web_bean_when_master_switch_off() {
        this.runner.withPropertyValues("myddd.web.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MydddWebProperties.class));
    }

    @Test
    void should_fail_startup_on_invalid_config_instead_of_first_request() {
        this.runner.withPropertyValues("myddd.web.i18n.default-locale=非法标签")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // 实测三层链：BeanCreationException("Invocation of init method failed")
                    // → FrameworkException（框架话术在这层）→ IllegalArgumentException（shape guard）。
                    // 只断第一层等于把框架的话术藏在包装里；AssertJ 的 hasFailed() 也不提供 getRootCause()。
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure.getCause()).isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("myddd.web.i18n.default-locale");
                    assertThat(failure).rootCause().isInstanceOf(IllegalArgumentException.class);
                });
    }
}
