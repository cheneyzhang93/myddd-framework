package org.deep361.myddd.domain.autoconfigure;

import org.deep361.myddd.domain.context.DomainModule;
import org.deep361.myddd.domain.context.DomainModuleRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MydddDomainAutoConfigurationTest {

    private static final String SINGLE = "org.deep361.myddd.domain.fixture.single";
    private static final String MULTI = "org.deep361.myddd.domain.fixture.multi";

    static class CustomFixture {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddDomainAutoConfiguration.class));

    @Test
    void should_build_registry_from_configured_scan_packages() {
        runner.withPropertyValues("myddd.domain.scan-packages=" + SINGLE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DomainModuleRegistry.class);

                    DomainModuleRegistry registry = context.getBean(DomainModuleRegistry.class);
                    assertThat(registry.names()).containsExactly("order", "order-sub");
                    assertThat(registry.get("order").scanPackages()).containsExactly(SINGLE);
                });
    }

    @Test
    void should_bind_comma_separated_packages_and_sort_modules() {
        runner.withPropertyValues("myddd.domain.scan-packages=" + SINGLE + "," + MULTI)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DomainModuleRegistry.class).names())
                            .containsExactly("billing", "order", "order-sub");
                });
    }

    @Test
    void should_fall_back_to_auto_configuration_packages_when_property_absent() {
        runner.withInitializer(context -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) context, SINGLE))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DomainModuleRegistry.class).names())
                            .containsExactly("order", "order-sub");
                });
    }

    @Test
    void should_fail_fast_when_neither_property_nor_auto_configuration_packages_present() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure()
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("myddd.domain.scan-packages");
        });
    }

    @Test
    void should_fail_fast_when_no_domain_module_found() {
        runner.withPropertyValues("myddd.domain.scan-packages=org.deep361.myddd.domain.fixture.plain")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("未发现任何 @Domain 领域模块");
                });
    }

    @Test
    void should_fail_fast_on_duplicate_module_names() {
        runner.withPropertyValues("myddd.domain.scan-packages=org.deep361.myddd.domain.fixture.dup")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("领域模块名重复：same");
                });
    }

    @Test
    void should_declare_autoconfiguration_in_imports_file() throws Exception {
        List<String> declared;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).as("自动装配 imports 文件必须存在").isNotNull();
            declared = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }

        assertThat(declared).containsExactly(
                MydddDomainAutoConfiguration.class.getName(),
                MydddDomainEventAutoConfiguration.class.getName(),
                MydddOutboxAutoConfiguration.class.getName());
    }

    @Test
    void should_back_off_when_user_provides_registry() {
        DomainModuleRegistry custom = DomainModuleRegistry.of(List.of(
                new DomainModule("custom", List.of("org.deep361.sample.custom"), CustomFixture.class)));

        runner.withPropertyValues("myddd.domain.scan-packages=org.deep361.myddd.domain.fixture.plain")
                .withBean(DomainModuleRegistry.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed(); // 用户自定义注册表 → 自动装配让位，不再触发"未发现模块"快速失败
                    assertThat(context.getBean(DomainModuleRegistry.class)).isSameAs(custom);
                });
    }
}
