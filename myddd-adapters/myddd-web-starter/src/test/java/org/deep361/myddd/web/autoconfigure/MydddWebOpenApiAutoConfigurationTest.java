package org.deep361.myddd.web.autoconfigure;

import io.swagger.v3.oas.models.OpenAPI;
import org.deep361.myddd.web.openapi.OpenApiInfoCustomizer;
import org.deep361.myddd.web.openapi.ResponseEnvelopeCustomizer;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 文档装配语义：两个定制器各自的开关边界，以及"框架的定制与业务的定制共存"。
 *
 * <p>装配类上 {@code @ConditionalOnClass} 用的是字符串形式（设计要点 9），它要求"springdoc 不在 classpath"
 * 才能观察到效果，而本模块的测试类路径上 springdoc 必然存在（optional 只影响对下游模块的传递，不影响本模块
 * 自己）⇒ 这一支不在这里测，由 Task 30 的不引 springdoc 样例应用做启动验证。
 */
class MydddWebOpenApiAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MydddWebAutoConfiguration.class, MydddWebOpenApiAutoConfiguration.class));

    @Test
    void should_assemble_both_customizers_as_global_type_by_default() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(OpenApiInfoCustomizer.class)
                    .hasSingleBean(ResponseEnvelopeCustomizer.class);
            // springdoc 是按 GlobalOpenApiCustomizer 这个类型收集的（设计要点 1），类型不对就等于没装配
            assertThat(context.getBeanNamesForType(GlobalOpenApiCustomizer.class)).hasSize(2);
        });
    }

    @Test
    void should_skip_both_customizers_when_openapi_switch_off_yet_keep_endpoints() {
        this.runner.withPropertyValues("myddd.web.openapi.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OpenApiInfoCustomizer.class)
                        .doesNotHaveBean(ResponseEnvelopeCustomizer.class));

        // 关的是"框架贡献"，不是 swagger 本身：文档端点由三方键 springdoc.api-docs.enabled 管（§0.3-6），
        // 属性类里因此不许出现任何形如 api-docs / swagger-ui 的代理开关。
        assertThat(MydddWebProperties.Openapi.class.getDeclaredFields())
                .extracting(field -> field.getName())
                .containsExactly("enabled", "title", "description", "version");
    }

    @Test
    void should_skip_both_customizers_when_master_switch_off_without_startup_failure() {
        // 组合条件（name = {"enabled", "openapi.enabled"}）在这里的落点：总开关关闭时本装配整体失效，
        // 于是它不会出现"依赖 MydddWebProperties、而 MydddWebAutoConfiguration 已关闭"的悬空注入。
        this.runner.withPropertyValues("myddd.web.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(MydddWebProperties.class)
                        .doesNotHaveBean(OpenApiInfoCustomizer.class)
                        .doesNotHaveBean(ResponseEnvelopeCustomizer.class));
    }

    @Test
    void should_still_assemble_framework_customizers_beside_business_one() {
        // 空实现即可：这条用例只关心"它在不在容器里"。两个框架定制器与业务定制器之间没有顺序依赖（设计要点 7）。
        GlobalOpenApiCustomizer business = (OpenAPI openApi) -> { };

        this.runner.withBean("businessCustomizer", GlobalOpenApiCustomizer.class, () -> business)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(GlobalOpenApiCustomizer.class)).hasSize(3);
                    assertThat(context.getBean(OpenApiInfoCustomizer.class)).isNotNull();
                    assertThat(context.getBean(ResponseEnvelopeCustomizer.class)).isNotNull();
                });
    }
}
