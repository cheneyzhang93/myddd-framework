package org.deep361.myddd.feign.autoconfigure;

import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.deep361.myddd.feign.error.MydddErrorDecoder;
import org.deep361.myddd.feign.trace.TracePropagationInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配条件与开关语义的验收：全部通过真实的 {@code myddd.feign.*} 键绑定驱动，不手工 new 装配类。
 *
 * <p>这里用 {@link ApplicationContextRunner}（无 web 环境）：Feign 的两个 bean 都不依赖 servlet，
 * 也不需要真实 Feign 客户端——{@code @ConditionalOnMissingBean} 与 {@code @ConditionalOnProperty} 的
 * 判定只发生在 bean 定义阶段。
 */
class MydddFeignAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddFeignAutoConfiguration.class));

    @Test
    void should_contribute_both_capabilities_without_any_configuration() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TracePropagationInterceptor.class);
            assertThat(context).hasSingleBean(MydddErrorDecoder.class);
        });
    }

    @Test
    void should_bind_every_documented_key_path() {
        runner.withPropertyValues("myddd.feign.enabled=true",
                "myddd.feign.trace.enabled=false",
                "myddd.feign.error-decoder.enabled=true").run(context -> {
                    MydddFeignProperties properties = context.getBean(MydddFeignProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getTrace().isEnabled()).isFalse();
                    assertThat(properties.getErrorDecoder().isEnabled()).isTrue();
                    assertThat(context).doesNotHaveBean(TracePropagationInterceptor.class);
                    assertThat(context).hasSingleBean(MydddErrorDecoder.class);
                });
    }

    @Test
    void should_contribute_nothing_when_the_master_switch_is_off() {
        runner.withPropertyValues("myddd.feign.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(RequestInterceptor.class)
                        .doesNotHaveBean(ErrorDecoder.class));
    }

    @Test
    void should_drop_only_the_interceptor_when_the_trace_switch_is_off() {
        runner.withPropertyValues("myddd.feign.trace.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TracePropagationInterceptor.class);
            assertThat(context).hasSingleBean(MydddErrorDecoder.class);
        });
    }

    @Test
    void should_drop_only_the_decoder_when_the_error_decoder_switch_is_off() {
        runner.withPropertyValues("myddd.feign.error-decoder.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(MydddErrorDecoder.class);
            assertThat(context).hasSingleBean(TracePropagationInterceptor.class);
        });
    }

    @Test
    void should_yield_the_decoder_slot_to_a_bean_the_application_declares() {
        ErrorDecoder applicationDecoder = (methodKey, response) -> new IllegalStateException("application wins");

        runner.withBean(ErrorDecoder.class, () -> applicationDecoder).run(context -> {
            assertThat(context).doesNotHaveBean(MydddErrorDecoder.class);
            assertThat(context).getBean(ErrorDecoder.class).isSameAs(applicationDecoder);
        });
    }

    @Test
    void should_contribute_the_interceptor_beside_the_application_s_own() {
        RequestInterceptor applicationInterceptor = template -> template.header("X-Tenant", "tenant-1");

        runner.withBean(RequestInterceptor.class, () -> applicationInterceptor).run(context -> {
            assertThat(context).hasSingleBean(TracePropagationInterceptor.class);
            assertThat(interceptorsOf(context))
                    .as("拦截器是共同贡献的形态：业务加自己的不构成『叫框架闭嘴』")
                    .hasSize(2)
                    .contains(context.getBean(TracePropagationInterceptor.class));
        });
    }

    @Test
    void should_keep_contributing_our_interceptor_when_the_application_declares_the_same_type() {
        runner.withBean(TracePropagationInterceptor.class, TracePropagationInterceptor::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(TracePropagationInterceptor.class))
                    .as("拦截器是共同贡献的：只有一份的诉求走 myddd.feign.trace.enabled=false，不靠 bean 遮蔽")
                    .hasSize(2);
        });
    }

    @Test
    void should_reuse_the_only_container_json_mapper() {
        JsonMapper containerMapper = JsonMapper.builder().build();

        runner.withBean(JsonMapper.class, () -> containerMapper).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MydddErrorDecoder.class);
        });
    }

    @Test
    void should_fail_fast_when_the_container_holds_two_json_mappers_without_a_primary() {
        runner.withBean("mapperA", JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean("mapperB", JsonMapper.class, () -> JsonMapper.builder().build())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
                            .hasStackTraceContaining("JsonMapper");
                });
    }

    /** 拦截器按类型收集，顺序不参与断言。 */
    private static java.util.List<RequestInterceptor> interceptorsOf(ApplicationContext context) {
        return java.util.List.copyOf(context.getBeansOfType(RequestInterceptor.class).values());
    }
}
